param([string]$ChildConfig, [switch]$CommandWorker)
# Shared by the Windows lifecycle entry points. Requires PowerShell 7.
function Assert-LifecycleHost {
    if ($PSVersionTable.PSVersion.Major -lt 7 -or -not $IsWindows) { throw '请在 Windows PowerShell 7 (pwsh) 中运行启停脚本。' }
}

function Import-LifecycleEnvironment([string]$ProjectRoot) {
    $dotenv = Join-Path $ProjectRoot '.env'
    if (Test-Path -LiteralPath $dotenv) {
        foreach ($line in Get-Content -LiteralPath $dotenv -Encoding UTF8) {
            if ($line -match '^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*?)\s*$') {
                [Environment]::SetEnvironmentVariable($matches[1], $matches[2].Trim('"').Trim("'"), 'Process')
            }
        }
    }
    $local = Join-Path $ProjectRoot '本地配置.ps1'
    if (Test-Path -LiteralPath $local) { . $local }
}

function Get-LifecycleConfiguration([string]$ProjectRoot, [hashtable]$Parameters = @{}, [switch]$IgnorePorts) {
    $project = if ($Parameters.ComposeProject) { $Parameters.ComposeProject } elseif ($env:COMPOSE_PROJECT_NAME) { $env:COMPOSE_PROJECT_NAME } else { 'silver-care-system' }
    if ($project -cnotmatch '^[a-z0-9][a-z0-9_-]*$') { throw 'ComposeProject 只能包含小写字母、数字、下划线和短横线。' }
    $defaults = @{MysqlPort=3308;RedisPort=6379;MilvusPort=19530;MilvusHealthPort=9091;RagPort=8001;BackendPort=9001;FrontendPort=8080}
    $variables = @{MysqlPort='MYSQL_PORT';RedisPort='REDIS_PORT';MilvusPort='MILVUS_PORT';MilvusHealthPort='MILVUS_HEALTH_PORT';RagPort='RAG_PORT';BackendPort='BACKEND_PORT';FrontendPort='FRONTEND_PORT'}
    $ports = @{}
    foreach ($key in $defaults.Keys) {
        $raw = if ($IgnorePorts) { $defaults[$key] } elseif ($Parameters.ContainsKey($key)) { $Parameters[$key] } else { [Environment]::GetEnvironmentVariable($variables[$key]) }
        if ([string]::IsNullOrEmpty([string]$raw)) { $raw = $defaults[$key] }
        $number = 0
        if (-not [int]::TryParse([string]$raw, [ref]$number) -or $number -lt 1 -or $number -gt 65535) { throw "$key 必须是 1..65535 的端口。" }
        $ports[$key] = $number
    }
    if (@($ports.Values | Select-Object -Unique).Count -ne $ports.Count) { throw '各服务端口必须互不重复。' }
    return @{projectRoot=[IO.Path]::GetFullPath($ProjectRoot);composeProject=$project;ports=$ports;statePath=(Join-Path $ProjectRoot ".runtime/lifecycle-$project.json")}
}

function Resolve-LifecycleProject([string]$ProjectRoot, [string]$ComposeProject) {
    if ($ComposeProject) { return $ComposeProject }
    # The selector is safe to read even if all credentials/configuration are gone.
    $dotenvProject=$null
    $dotenv=Join-Path $ProjectRoot '.env'
    if (Test-Path -LiteralPath $dotenv) {
        foreach ($line in Get-Content -LiteralPath $dotenv -Encoding UTF8) {
            if ($line -match '^\s*COMPOSE_PROJECT_NAME\s*=\s*(.*?)\s*$') { $dotenvProject=$matches[1].Trim('"').Trim("'") }
        }
    }
    $directory=Join-Path $ProjectRoot '.runtime'
    $candidates=@()
    if (Test-Path -LiteralPath $directory) {
        $candidates=@(Get-ChildItem -LiteralPath $directory -File -Filter 'lifecycle-*.json' | ForEach-Object {
            if ($_.BaseName -cmatch '^lifecycle-([a-z0-9][a-z0-9_-]*)$') { $matches[1] }
        })
    }
    if ($env:COMPOSE_PROJECT_NAME -and $env:COMPOSE_PROJECT_NAME -cin $candidates) { return $env:COMPOSE_PROJECT_NAME }
    if ($dotenvProject -and $dotenvProject -cin $candidates) { return $dotenvProject }
    if ($candidates.Count -eq 1) { return $candidates[0] }
    if ($candidates.Count -gt 1) { throw '本目录存在多个实例状态，请显式提供 -ComposeProject；未操作任何服务。' }
    if ($env:COMPOSE_PROJECT_NAME) { return $env:COMPOSE_PROJECT_NAME }
    if ($dotenvProject) { return $dotenvProject }
    return 'silver-care-system'
}

function Read-LifecycleState([string]$Path, [string]$ProjectRoot, [string]$ComposeProject) {
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    try { $state = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json -AsHashtable } catch { throw '启停状态损坏；为避免误停其他服务，已拒绝操作。' }
    if ($state.schemaVersion -ne 1 -or $state.projectRoot -ne [IO.Path]::GetFullPath($ProjectRoot) -or $state.composeProject -cne $ComposeProject) { throw '启停状态与本目录或 Compose 项目不匹配。' }
    foreach ($identity in $state.processes) { if ($identity.projectRoot -ne $state.projectRoot) { throw '进程状态目录与当前项目不匹配。' } }
    foreach ($identity in $state.containers) { if ($identity.workingDirectory -ne $state.projectRoot -or $identity.composeProject -cne $ComposeProject) { throw '容器状态所有权与当前项目不匹配。' } }
    return $state
}

function Get-LifecycleDigest([byte[]]$Bytes) {
    $algorithm=[Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($algorithm.ComputeHash($Bytes)).Replace('-','') } finally { $algorithm.Dispose() }
}

function Get-WindowsToolPath([string]$Path) {
    if (-not $IsWindows -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $Path }
    if (-not ('SilverCare.NativePaths' -as [type])) {
        Add-Type -TypeDefinition @'
using System.Runtime.InteropServices;
using System.Text;
namespace SilverCare {
    public static class NativePaths {
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        public static extern uint GetShortPathName(string longPath, StringBuilder shortPath, uint bufferLength);
    }
}
'@
    }
    $buffer=[Text.StringBuilder]::new(32768)
    $length=[SilverCare.NativePaths]::GetShortPathName($Path,$buffer,[uint32]$buffer.Capacity)
    if ($length -gt 0 -and $length -lt $buffer.Capacity) {
        $short=$buffer.ToString()
        if ($short.Length -lt $Path.Length -and (Test-Path -LiteralPath $short -PathType Leaf)) { return $short }
    }
    return $Path
}

function Start-LifecycleSupervisor([Diagnostics.ProcessStartInfo]$StartInfo) {
    if (-not ('SilverCare.NativeHandles' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
namespace SilverCare {
    public static class NativeHandles {
        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern IntPtr GetStdHandle(int standardHandle);
        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern bool GetHandleInformation(IntPtr handle, out uint flags);
        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern bool SetHandleInformation(IntPtr handle, uint mask, uint flags);
    }
}
'@
    }
    $changed=[Collections.Generic.List[object]]::new()
    try {
        # .NET redirects the child's standard handles, but CreateProcess can
        # still inherit the launcher's original capture pipes on Windows.
        foreach ($number in @(-10,-11,-12)) {
            $handle=[SilverCare.NativeHandles]::GetStdHandle($number)
            if ($handle -eq [IntPtr]::Zero -or $handle -eq [IntPtr]::new(-1)) { continue }
            $flags=[uint32]0
            if ([SilverCare.NativeHandles]::GetHandleInformation($handle,[ref]$flags) -and ($flags -band 1)) {
                if (-not [SilverCare.NativeHandles]::SetHandleInformation($handle,1,0)) { throw '无法隔离日志进程的继承句柄，未启动应用。' }
                $changed.Add(@{handle=$handle;flags=($flags -band 1)})
            }
        }
        return [Diagnostics.Process]::Start($StartInfo)
    } finally {
        foreach ($entry in $changed) { [void][SilverCare.NativeHandles]::SetHandleInformation($entry.handle,1,$entry.flags) }
    }
}

function Enter-LifecycleLock([string]$StatePath) {
    $digest=Get-LifecycleDigest ([Text.Encoding]::UTF8.GetBytes($StatePath.ToLowerInvariant()))
    $mutex=[Threading.Mutex]::new($false, "Local\SilverCareLifecycle-$digest")
    try { $acquired=$mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $acquired=$true }
    if (-not $acquired) { $mutex.Dispose(); throw '本 Compose 项目正在进行另一项启停操作，请等待完成。' }
    return $mutex
}

function Get-LifecycleFingerprint([string]$ProjectRoot) {
    $names=@('MYSQL_ROOT_PASSWORD','DB_USERNAME','DB_PASSWORD','DB_URL','REDIS_PASSWORD','SPRING_REDIS_HOST','SPRING_REDIS_PORT','JWT_SECRET','AI_RAG_INTERNAL_TOKEN','RAG_INTERNAL_TOKEN','MINIO_ROOT_USER','MINIO_ROOT_PASSWORD','MILVUS_ROOT_PASSWORD','RAG_MILVUS_TOKEN','AI_RAG_URL','RAG_MILVUS_URI','RAG_DATA_FILE','AI_PROVIDER_ENABLED','AI_PROVIDER_URL','AI_PROVIDER_API_KEY','AI_PROVIDER_MODEL','AI_OCR_ENABLED','AI_OCR_COMMAND','AI_OCR_LANGUAGE','AI_OCR_DATA_DIR','LEGACY_AES_IV','LEGACY_AES_KEY')
    $values=foreach ($name in $names) { "$name=$([Environment]::GetEnvironmentVariable($name))" }
    if ($ProjectRoot) {
        foreach ($filename in @('.env','本地配置.ps1')) {
            $path=Join-Path $ProjectRoot $filename
            if (Test-Path -LiteralPath $path) { $values += "$filename=$((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash)" }
        }
    }
    return (Get-LifecycleDigest ([Text.Encoding]::UTF8.GetBytes(($values -join "`n"))))
}

function Test-ContainerPublishedPort($Identity, [string]$DockerPath, [int]$HostPort, [int]$ContainerPort) {
    if (-not (Test-ContainerIdentity $Identity $DockerPath)) { return $false }
    $raw=& $DockerPath inspect $Identity.id
    if ($LASTEXITCODE -ne 0) { return $false }
    $live=@($raw | ConvertFrom-Json)[0]
    return @($live.NetworkSettings.Ports."$ContainerPort/tcp" | Where-Object { $_.HostIp -eq '127.0.0.1' -and $_.HostPort -eq [string]$HostPort }).Count -eq 1
}

function Test-LifecycleAuthorization([string]$Url) {
    $response=Invoke-WebRequest $Url -NoProxy:(([uri]$Url).IsLoopback) -TimeoutSec 5 -SkipHttpErrorCheck
    return $response.StatusCode -eq 401 -or $response.StatusCode -eq 403
}

function Save-LifecycleState($State, [string]$Path) {
    New-Item -ItemType Directory -Force -Path (Split-Path $Path) | Out-Null
    $temporary = "$Path.$([Guid]::NewGuid().ToString('N')).tmp"
    try {
        [IO.File]::WriteAllText($temporary, ($State | ConvertTo-Json -Depth 15), [Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath $temporary -Destination $Path -Force
    } finally { if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force } }
}

function Get-ProcessIdentity([int]$ProcessId, [string]$ProjectRoot, [string]$Name) {
    $live = Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction SilentlyContinue
    if (-not $live -or -not $live.ExecutablePath -or -not $live.CommandLine -or -not $live.CreationDate) { return $null }
    return @{name=$Name;pid=$ProcessId;createdUtc=$live.CreationDate.ToUniversalTime().ToString('o');executablePath=$live.ExecutablePath;commandLine=$live.CommandLine;projectRoot=[IO.Path]::GetFullPath($ProjectRoot)}
}

function Test-ProjectCommandLine([string]$CommandLine, [string]$ProjectRoot) {
    $pattern = '(?:^|[\s"''=])' + [regex]::Escape($ProjectRoot.TrimEnd('\','/')) + '(?=[\\/"''\s]|$)'
    return [regex]::IsMatch($CommandLine, $pattern, [Text.RegularExpressions.RegexOptions]::IgnoreCase)
}

function Test-ProcessIdentity($Identity) {
    if (-not $Identity -or $Identity.pid -le 0 -or -not $Identity.createdUtc -or -not $Identity.executablePath -or -not $Identity.commandLine -or -not $Identity.projectRoot) { return $false }
    $live = Get-ProcessIdentity -ProcessId $Identity.pid -ProjectRoot $Identity.projectRoot -Name $Identity.name
    if (-not $live) { return $false }
    try { $sameCreation=([DateTimeOffset]$live.createdUtc).UtcTicks -eq ([DateTimeOffset]$Identity.createdUtc).UtcTicks } catch { return $false }
    return [bool]($sameCreation -and $live.executablePath -eq $Identity.executablePath -and $live.commandLine -ceq $Identity.commandLine -and (Test-ProjectCommandLine $live.commandLine $Identity.projectRoot))
}

function Stop-OwnedProcess($Identity) {
    if (-not (Test-ProcessIdentity $Identity)) { Write-Warning '进程已退出或身份不符，跳过停止。'; return }
    # Capture identities before stopping; recheck each process to defeat stale/reused PIDs.
    $pending = [Collections.Generic.Queue[object]]::new()
    $pending.Enqueue($Identity)
    $owned = [Collections.Generic.List[object]]::new()
    while ($pending.Count) {
        $parent = $pending.Dequeue()
        if (-not (Test-ProcessIdentity $parent)) { continue }
        $owned.Add($parent)
        foreach ($child in @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $($parent.pid)" -ErrorAction SilentlyContinue)) {
            $childIdentity = Get-ProcessIdentity -ProcessId $child.ProcessId -ProjectRoot $Identity.projectRoot -Name $Identity.name
            if ($childIdentity -and [DateTime]$childIdentity.createdUtc -ge [DateTime]$parent.createdUtc -and (Test-ProjectCommandLine $childIdentity.commandLine $Identity.projectRoot)) { $pending.Enqueue($childIdentity) }
        }
    }
    for ($index = $owned.Count - 1; $index -ge 0; $index--) {
        $identityToStop=$owned[$index]
        $processToStop=$null
        try {
            # Bind a handle before the final identity check: Kill must target
            # this process object even if its numeric PID is subsequently reused.
            $processToStop=[Diagnostics.Process]::GetProcessById($identityToStop.pid)
            $null=$processToStop.SafeHandle
            $handleCreated=$processToStop.StartTime.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.ffffff')
            $recordedCreated=([DateTimeOffset]$identityToStop.createdUtc).UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ss.ffffff')
            if (Test-ProcessIdentity $identityToStop) {
                if ($handleCreated -ne $recordedCreated) { throw '进程句柄创建时间与状态不符，已拒绝停止；请保留状态后重试。' }
                $processToStop.Kill()
                if (-not $processToStop.WaitForExit(5000)) { throw '自有进程停止超时，请重试停止。' }
            }
        } catch {
            # A child exiting can make its waiting logger exit concurrently.
            # Retain errors when the same owned process is still alive.
            if (Test-ProcessIdentity $identityToStop) { throw }
        } finally { if ($processToStop) { $processToStop.Dispose() } }
    }
    if ($Identity.launchConfigPath) { Remove-LifecycleLaunchFiles $Identity.launchConfigPath $Identity.projectRoot }
}

function Remove-LifecycleLaunchFiles([string]$ConfigPath, [string]$ProjectRoot) {
    if (-not $ConfigPath) { return }
    $directory=[IO.Path]::GetFullPath((Join-Path $ProjectRoot '.runtime/logs')).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    $resolved=[IO.Path]::GetFullPath($ConfigPath)
    if (-not $resolved.StartsWith($directory,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -cnotmatch '^[A-Za-z0-9_-]+-[a-f0-9]{32}\.launch\.json$') { return }
    foreach ($suffix in @('', '.gate', '.identity', '.identity.tmp')) {
        if (Test-Path -LiteralPath "$resolved$suffix" -PathType Leaf) { Remove-Item -LiteralPath "$resolved$suffix" -Force -ErrorAction SilentlyContinue }
    }
}

function Initialize-LifecycleCommandJob {
    if ('SilverCare.CommandJob' -as [type]) { return }
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
namespace SilverCare {
    public sealed class CommandJob : IDisposable {
        [StructLayout(LayoutKind.Sequential)] struct BasicLimits {
            public long ProcessTime, JobTime;
            public uint Flags;
            public UIntPtr MinimumWorkingSet, MaximumWorkingSet;
            public uint ActiveProcesses;
            public UIntPtr Affinity;
            public uint Priority, Scheduling;
        }
        [StructLayout(LayoutKind.Sequential)] struct Counters {
            public ulong ReadOperations, WriteOperations, OtherOperations, ReadBytes, WriteBytes, OtherBytes;
        }
        [StructLayout(LayoutKind.Sequential)] struct ExtendedLimits {
            public BasicLimits Basic;
            public Counters Io;
            public UIntPtr ProcessMemory, JobMemory, PeakProcessMemory, PeakJobMemory;
        }
        [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
        static extern SafeFileHandle CreateJobObject(IntPtr attributes, string name);
        [DllImport("kernel32.dll", SetLastError=true)]
        static extern bool SetInformationJobObject(SafeFileHandle job, int informationClass, IntPtr information, uint length);
        [DllImport("kernel32.dll", SetLastError=true)]
        static extern bool AssignProcessToJobObject(SafeFileHandle job, SafeProcessHandle process);
        readonly SafeFileHandle handle;
        public CommandJob() {
            // Anonymous and non-inheritable: only the calling starter owns this handle.
            handle=CreateJobObject(IntPtr.Zero, null);
            if (handle.IsInvalid) throw new Win32Exception(Marshal.GetLastWin32Error());
            var limits=new ExtendedLimits();
            limits.Basic.Flags=0x2000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            int size=Marshal.SizeOf(typeof(ExtendedLimits));
            IntPtr memory=Marshal.AllocHGlobal(size);
            try {
                Marshal.StructureToPtr(limits, memory, false);
                if (!SetInformationJobObject(handle, 9, memory, (uint)size)) {
                    int error=Marshal.GetLastWin32Error(); handle.Dispose(); throw new Win32Exception(error);
                }
            } finally { Marshal.FreeHGlobal(memory); }
        }
        public void Assign(Process process) {
            if (!AssignProcessToJobObject(handle, process.SafeHandle)) throw new Win32Exception(Marshal.GetLastWin32Error());
        }
        public void Dispose() { handle.Dispose(); }
    }
}
'@
}

function Write-LifecycleCommandEvent([string]$LogPath, [string]$Event, [Diagnostics.Stopwatch]$Timer, [int]$TimeoutSeconds, [hashtable]$Details=@{}) {
    $record=@{utc=[DateTime]::UtcNow.ToString('o');event=$Event;stage=[IO.Path]::GetFileName($LogPath);timeoutSeconds=$TimeoutSeconds;elapsedMilliseconds=$Timer.ElapsedMilliseconds}
    foreach ($key in $Details.Keys) { $record[$key]=$Details[$key] }
    [IO.File]::AppendAllText("$LogPath.events.jsonl", (($record | ConvertTo-Json -Compress) + [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
}

function Invoke-LifecycleCommand([string]$FilePath, [string[]]$Arguments, [string]$WorkingDirectory, [string]$LogPath, [ValidateRange(0,86400)][int]$TimeoutSeconds=0) {
    $timer=[Diagnostics.Stopwatch]::StartNew()
    $outStream=$null; $errStream=$null; $worker=$null; $job=$null
    $timedOut=$false; $exitCode=$null; $drainDeadline=$null; $stdinClosed=$false
    try {
        Write-LifecycleCommandEvent $LogPath 'starting' $timer $TimeoutSeconds
        # No subprocess may exist until both local output resources are usable.
        $outStream=[IO.FileStream]::new($LogPath,[IO.FileMode]::Create,[IO.FileAccess]::Write,[IO.FileShare]::ReadWrite,1,[IO.FileOptions]::Asynchronous)
        $errStream=[IO.FileStream]::new("$LogPath.stderr",[IO.FileMode]::Create,[IO.FileAccess]::Write,[IO.FileShare]::ReadWrite,1,[IO.FileOptions]::Asynchronous)
        Initialize-LifecycleCommandJob
        $job=[SilverCare.CommandJob]::new()
        $info=[Diagnostics.ProcessStartInfo]::new()
        $info.FileName=(Get-Process -Id $PID).Path
        $info.WorkingDirectory=$WorkingDirectory; $info.UseShellExecute=$false; $info.CreateNoWindow=$true
        $info.WindowStyle=[Diagnostics.ProcessWindowStyle]::Hidden
        $info.RedirectStandardInput=$true; $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
        $info.StandardInputEncoding=[Text.UTF8Encoding]::new($false)
        foreach ($argument in @('-NoProfile','-NonInteractive','-File',$MyInvocation.MyCommand.ScriptBlock.File,'-CommandWorker')) { $info.ArgumentList.Add($argument) }
        $worker=Start-LifecycleSupervisor $info
        # The worker is inert until this succeeds. Never fall back to an
        # uncontained command when nested-job assignment is unavailable.
        $job.Assign($worker)
        $stdout=$worker.StandardOutput.BaseStream.CopyToAsync($outStream)
        $stderr=$worker.StandardError.BaseStream.CopyToAsync($errStream)
        Write-LifecycleCommandEvent $LogPath 'started' $timer $TimeoutSeconds @{workerPid=$worker.Id}
        # Arguments/environment are never persisted in a command file or event.
        $commandArguments=[string[]]@()
        if ($null -ne $Arguments) { $commandArguments=$Arguments }
        $payload=@{filePath=$FilePath;arguments=$commandArguments;workingDirectory=$WorkingDirectory} | ConvertTo-Json -Compress -Depth 5
        $send=$worker.StandardInput.WriteLineAsync($payload)
        while ($true) {
            if ($send.IsFaulted) { [void]$send.GetAwaiter().GetResult() }
            if (-not $stdinClosed -and $send.IsCompleted) {
                [void]$send.GetAwaiter().GetResult()
                $worker.StandardInput.Close()
                $stdinClosed=$true
            }
            if ($stdout.IsFaulted) { [void]$stdout.GetAwaiter().GetResult() }
            if ($stderr.IsFaulted) { [void]$stderr.GetAwaiter().GetResult() }
            if ($worker.HasExited) {
                $exitCode=$worker.ExitCode
                if ($job) { $job.Dispose(); $job=$null; $drainDeadline=$timer.Elapsed.TotalSeconds + 5 }
                if ($stdout.IsCompleted -and $stderr.IsCompleted) {
                    [void]$stdout.GetAwaiter().GetResult(); [void]$stderr.GetAwaiter().GetResult()
                    break
                }
            }
            if ($TimeoutSeconds -gt 0 -and $timer.Elapsed.TotalSeconds -ge $TimeoutSeconds) { $timedOut=$true; throw 'deadline' }
            if ($null -ne $drainDeadline -and $timer.Elapsed.TotalSeconds -ge $drainDeadline) { throw 'pipe-drain' }
            Start-Sleep -Milliseconds 50
        }
        if ($exitCode -ne 0) { throw 'nonzero' }
        Write-LifecycleCommandEvent $LogPath 'completed' $timer $TimeoutSeconds @{exitCode=$exitCode}
    } catch {
        $event=if ($timedOut) { 'timeout' } else { 'failed' }
        $details=@{}
        if ($null -ne $exitCode) { $details.exitCode=$exitCode }
        try { Write-LifecycleCommandEvent $LogPath $event $timer $TimeoutSeconds $details } catch { }
        $description=if ($timedOut) { "超时（$TimeoutSeconds 秒）" } elseif ($null -ne $exitCode) { "失败（exit $exitCode）" } else { '初始化或日志 I/O 失败' }
        throw "命令阶段 $([IO.Path]::GetFileName($LogPath)) $description；日志：$LogPath；标准错误：$LogPath.stderr；事件：$LogPath.events.jsonl"
    } finally {
        if ($job) { $job.Dispose() }
        if ($worker) {
            # Closing the job also handles an already exited root whose child
            # still holds a pipe. Cleanup waits are bounded separately.
            if (-not $worker.HasExited) { [void]$worker.WaitForExit(5000) }
            $worker.Dispose()
        }
        if ($outStream) { $outStream.Dispose() }
        if ($errStream) { $errStream.Dispose() }
    }
}

function Invoke-LifecycleCommandWorker {
    # This private pipe carries UTF-8 JSON, independently of the Windows OEM code page.
    [Console]::InputEncoding=[Text.UTF8Encoding]::new($false)
    $line=[Console]::In.ReadLine()
    if ([string]::IsNullOrEmpty($line)) { return 125 }
    $payload=$line | ConvertFrom-Json -AsHashtable
    $child=$null
    try {
        $info=[Diagnostics.ProcessStartInfo]::new()
        $info.FileName=$payload.filePath; $info.WorkingDirectory=$payload.workingDirectory
        $info.UseShellExecute=$false; $info.CreateNoWindow=$true
        $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
        foreach ($argument in $payload.arguments) { $info.ArgumentList.Add([string]$argument) }
        $child=[Diagnostics.Process]::Start($info)
        $stdout=$child.StandardOutput.BaseStream.CopyToAsync([Console]::OpenStandardOutput())
        $stderr=$child.StandardError.BaseStream.CopyToAsync([Console]::OpenStandardError())
        $child.WaitForExit()
        [void]$stdout.GetAwaiter().GetResult(); [void]$stderr.GetAwaiter().GetResult()
        return $child.ExitCode
    } catch {
        [Console]::Error.WriteLine('命令工作进程失败。')
        return 125
    } finally { if ($child) { $child.Dispose() } }
}

function Get-LifecycleContainers([string]$DockerPath, [string]$ComposeProject, [string]$ProjectRoot) {
    $ids = @(& $DockerPath ps -aq --filter "label=com.docker.compose.project=$ComposeProject")
    if ($LASTEXITCODE -ne 0) { throw '无法读取 Compose 容器。' }
    $result = @()
    foreach ($id in $ids) {
        if (-not $id) { continue }
        $raw = & $DockerPath inspect $id
        if ($LASTEXITCODE -ne 0) { throw '无法检查 Compose 容器。' }
        $container = @($raw | ConvertFrom-Json)[0]
        $labels = $container.Config.Labels
        if ($labels.'com.docker.compose.project.working_dir' -ne $ProjectRoot) { throw 'Compose 项目名已被其他目录使用；请更换 ComposeProject。' }
        $result += @{id=$container.Id;service=$labels.'com.docker.compose.service';composeProject=$ComposeProject;workingDirectory=$ProjectRoot;running=[bool]$container.State.Running}
    }
    return $result
}

function Start-LifecycleInfrastructure($State, [string]$StatePath, [string]$DockerPath, [string[]]$ComposeArguments, [string]$LogPath) {
    # Persist every exact ID before starting containers. A killed shell can then
    # be recovered by stop/start without adopting another directory's resources.
    try {
        & $DockerPath @ComposeArguments create --build --no-recreate 2>&1 | Out-File -LiteralPath $LogPath -Encoding utf8
        $createExit=$LASTEXITCODE
    } finally {
        $State.containers=@(Get-LifecycleContainers $DockerPath $State.composeProject $State.projectRoot)
        Save-LifecycleState $State $StatePath
    }
    if ($createExit -ne 0) { throw 'Docker Compose 创建失败；已创建的停止容器和数据卷保留，可修复后重试。' }
    try {
        & $DockerPath @ComposeArguments start 2>&1 | Out-File -LiteralPath $LogPath -Append -Encoding utf8
        $startExit=$LASTEXITCODE
    } finally {
        $State.containers=@(Get-LifecycleContainers $DockerPath $State.composeProject $State.projectRoot)
        Save-LifecycleState $State $StatePath
    }
    if ($startExit -ne 0) { throw 'Docker Compose 启动失败。' }
}

function Test-ContainerIdentity($Identity, [string]$DockerPath) {
    if (-not $Identity -or $Identity.id -cnotmatch '^[a-f0-9]{64}$' -or -not $Identity.composeProject -or -not $Identity.service -or -not $Identity.workingDirectory) { return $false }
    $raw = & $DockerPath inspect $Identity.id 2>$null
    if ($LASTEXITCODE -ne 0) { return $false }
    try { $live = @($raw | ConvertFrom-Json)[0] } catch { return $false }
    return [bool]($live.Id -ceq $Identity.id -and $live.Config.Labels.'com.docker.compose.project' -ceq $Identity.composeProject -and $live.Config.Labels.'com.docker.compose.service' -ceq $Identity.service -and $live.Config.Labels.'com.docker.compose.project.working_dir' -eq $Identity.workingDirectory)
}

function Stop-OwnedContainer($Identity, [string]$DockerPath) {
    if (Test-ContainerIdentity $Identity $DockerPath) {
        & $DockerPath stop $Identity.id | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "容器停止失败：$($Identity.service)" }
    } else { Write-Warning '容器不存在或 Compose 所有权不符，跳过停止。' }
}

function Assert-LifecyclePort([int]$Port, $Identity, [bool]$OwnedContainer = $false) {
    $listeners = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
    if ($listeners.Count -eq 0) { return }
    if ($Identity -and (Test-ProcessIdentity $Identity)) {
        $foreign=@($listeners | Where-Object { -not (Test-ProcessInOwnedTree -ProcessId $_.OwningProcess -RootIdentity $Identity) })
        if ($foreign.Count -eq 0) { return }
    }
    if ($OwnedContainer) { return }
    throw "端口 $Port 被其他进程占用；未启动或接管该服务。"
}

function Test-ProcessInOwnedTree([int]$ProcessId, $RootIdentity) {
    if (-not (Test-ProcessIdentity $RootIdentity)) { return $false }
    $visited=@{}
    while ($ProcessId -gt 0 -and -not $visited.ContainsKey($ProcessId)) {
        if ($ProcessId -eq $RootIdentity.pid) { return (Test-ProcessIdentity $RootIdentity) }
        $visited[$ProcessId]=$true
        $current=Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction SilentlyContinue
        if (-not $current -or -not $current.CreationDate -or -not (Test-ProjectCommandLine $current.CommandLine $RootIdentity.projectRoot) -or $current.CreationDate.ToUniversalTime().Ticks -lt ([DateTimeOffset]$RootIdentity.createdUtc).UtcTicks) { return $false }
        $ProcessId=[int]$current.ParentProcessId
    }
    return $false
}

function Wait-LifecycleCondition([scriptblock]$Condition, [int]$TimeoutSeconds, [string]$Description) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        try { if (& $Condition) { return } } catch { }
        Start-Sleep -Milliseconds 1000
    } while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    throw "$Description 未在 $TimeoutSeconds 秒内通过健康检查。"
}

function Start-LifecycleProcess([string]$Name, [string]$FilePath, [string[]]$Arguments, [string]$WorkingDirectory, [string]$ProjectRoot, [string]$LogDirectory, [scriptblock]$OnIdentity) {
    $configPath = Join-Path $LogDirectory ("$Name-" + [Guid]::NewGuid().ToString('N') + '.launch.json')
    $config = @{name=$Name;filePath=$FilePath;arguments=$Arguments;workingDirectory=$WorkingDirectory;projectRoot=$ProjectRoot;stdout=(Join-Path $LogDirectory "$Name.log");stderr=(Join-Path $LogDirectory "$Name.err.log");identityPath="$configPath.identity";gatePath="$configPath.gate"}
    [IO.File]::WriteAllText($configPath, ($config | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = (Get-Process -Id $PID).Path
    $info.UseShellExecute=$false; $info.CreateNoWindow=$true; $info.WindowStyle=[Diagnostics.ProcessWindowStyle]::Hidden
    $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    foreach ($argument in @('-NoProfile','-File',(Join-Path $ProjectRoot '启停工具.ps1'),'-ChildConfig',$configPath)) { $info.ArgumentList.Add($argument) }
    $supervisor = Start-LifecycleSupervisor $info
    # A detached logger must not keep the launcher's console/automation pipes open.
    [void]$supervisor.StandardOutput.BaseStream.CopyToAsync([IO.Stream]::Null)
    [void]$supervisor.StandardError.BaseStream.CopyToAsync([IO.Stream]::Null)
    $supervisorIdentity = Get-ProcessIdentity -ProcessId $supervisor.Id -ProjectRoot $ProjectRoot -Name "$Name-logger"
    $identity=$null
    try {
        if (-not (Test-ProcessIdentity $supervisorIdentity)) { throw "$Name 日志进程身份记录失败，未许可启动应用。" }
        $supervisorIdentity.launchConfigPath=$configPath
        if ($OnIdentity) { & $OnIdentity $supervisorIdentity | Out-Null }
        # No application can exist until its recorded supervisor is recoverable.
        [IO.File]::WriteAllText($config.gatePath,'start',[Text.UTF8Encoding]::new($false))
        Wait-LifecycleCondition -TimeoutSeconds 15 -Description "$Name 进程身份记录" -Condition { Test-Path -LiteralPath $config.identityPath -PathType Leaf }
        $identity = Get-Content -LiteralPath $config.identityPath -Raw | ConvertFrom-Json -AsHashtable
        if (-not (Test-ProcessIdentity $identity)) { throw "$Name 启动后立即退出，请检查 $LogDirectory。" }
        if ($OnIdentity) { & $OnIdentity $identity | Out-Null }
        return @($identity, $supervisorIdentity)
    } catch {
        try { if ($identity) { Stop-OwnedProcess $identity }; Stop-OwnedProcess $supervisorIdentity } catch { Write-Warning '本次应用进程清理失败，请依据已保存状态执行停止。' }
        throw
    } finally {
        Remove-LifecycleLaunchFiles $configPath $ProjectRoot
    }
}

if ($CommandWorker) { exit (Invoke-LifecycleCommandWorker) }

if ($ChildConfig) {
    $configuration = Get-Content -LiteralPath $ChildConfig -Raw | ConvertFrom-Json -AsHashtable
    $outStream=$null; $errStream=$null; $childProcess=$null
    $outCopy=$null; $errCopy=$null; $childId=$null; $childExit=$null
    $supervisorClock=[Diagnostics.Stopwatch]::StartNew()
    $supervisorLog="$($configuration.stdout).supervisor"
    $supervisorDetails=@{loggerPid=$PID;launchConfig=[IO.Path]::GetFileName($ChildConfig)}
    try {
        # Validate diagnostic output before spawning. Later diagnostic failures
        # must not change application behavior or mask its original exception.
        Write-LifecycleCommandEvent $supervisorLog 'waiting' $supervisorClock 0 $supervisorDetails
        Wait-LifecycleCondition -TimeoutSeconds 15 -Description '应用启动许可' -Condition { Test-Path -LiteralPath $configuration.gatePath -PathType Leaf }
        # Validate both output resources before an application can exist.
        $outStream = [IO.FileStream]::new($configuration.stdout, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::ReadWrite, 1, [IO.FileOptions]::Asynchronous)
        $errStream = [IO.FileStream]::new($configuration.stderr, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::ReadWrite, 1, [IO.FileOptions]::Asynchronous)
        $childInfo = [Diagnostics.ProcessStartInfo]::new()
        $childInfo.FileName=$configuration.filePath; $childInfo.WorkingDirectory=$configuration.workingDirectory
        $childInfo.UseShellExecute=$false; $childInfo.CreateNoWindow=$true; $childInfo.RedirectStandardOutput=$true; $childInfo.RedirectStandardError=$true
        # Keep long-lived application input owned by this logger, independently
        # of the launching shell's console/pipe lifetime. Dispose closes it on stop.
        $childInfo.RedirectStandardInput=$true
        foreach ($argument in $configuration.arguments) { $childInfo.ArgumentList.Add($argument) }
        $childProcess = [Diagnostics.Process]::Start($childInfo)
        $childId=$childProcess.Id
        $supervisorDetails.childPid=$childId
        try { Write-LifecycleCommandEvent $supervisorLog 'child-started' $supervisorClock 0 $supervisorDetails } catch { }
        $outCopy=$childProcess.StandardOutput.BaseStream.CopyToAsync($outStream)
        $errCopy=$childProcess.StandardError.BaseStream.CopyToAsync($errStream)
        $identity = Get-ProcessIdentity -ProcessId $childProcess.Id -ProjectRoot $configuration.projectRoot -Name $configuration.name
        if (-not $identity) { throw '无法记录本次应用身份，已拒绝继续启动。' }
        $identityTemporary="$($configuration.identityPath).tmp"
        [IO.File]::WriteAllText($identityTemporary, ($identity | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
        if (Test-Path -LiteralPath $configuration.identityPath -PathType Container) { throw '进程身份发布路径是目录，已拒绝继续启动。' }
        Move-Item -LiteralPath $identityTemporary -Destination $configuration.identityPath -Force -ErrorAction Stop
        while (-not $childProcess.WaitForExit(250)) {
            if ($outCopy.IsFaulted) { [void]$outCopy.GetAwaiter().GetResult() }
            if ($errCopy.IsFaulted) { [void]$errCopy.GetAwaiter().GetResult() }
        }
        $childExit=$childProcess.ExitCode
        try { Write-LifecycleCommandEvent $supervisorLog 'child-exited' $supervisorClock 0 ($supervisorDetails + @{exitCode=$childExit}) } catch { }
        [void]$outCopy.GetAwaiter().GetResult(); [void]$errCopy.GetAwaiter().GetResult()
    } catch {
        try {
            $cause=$_.Exception
            while ($cause.InnerException) { $cause=$cause.InnerException }
            $failure=$supervisorDetails + @{errorType=$cause.GetType().FullName;hresult=$cause.HResult;
                outTaskStatus=$(if($outCopy){[string]$outCopy.Status}else{'not-started'});
                errTaskStatus=$(if($errCopy){[string]$errCopy.Status}else{'not-started'})}
            if ($childProcess) {
                $failure.childHasExited=$childProcess.HasExited
                if ($childProcess.HasExited) { $failure.childExitCode=$childProcess.ExitCode }
            }
            Write-LifecycleCommandEvent $supervisorLog 'logger-catch' $supervisorClock 0 $failure
        } catch { }
        # This is the exact Process object just created above, not a PID lookup.
        # A failed identity publication must never orphan an unrecorded service.
        if ($childProcess -and -not $childProcess.HasExited) {
            try { Write-LifecycleCommandEvent $supervisorLog 'logger-killing-child' $supervisorClock 0 $supervisorDetails } catch { }
            $childProcess.Kill($true)
            if (-not $childProcess.WaitForExit(5000)) { throw '本次应用初始化失败且回收超时，请根据已保存的日志进程状态重试停止。' }
            $childExit=$childProcess.ExitCode
            try { Write-LifecycleCommandEvent $supervisorLog 'logger-killed-child' $supervisorClock 0 ($supervisorDetails + @{exitCode=$childExit}) } catch { }
        }
        throw
    } finally {
        if ($outStream) { $outStream.Dispose() }
        if ($errStream) { $errStream.Dispose() }
        if ($childProcess) { $childProcess.Dispose() }
        try {
            $finished=$supervisorDetails + @{outTaskStatus=$(if($outCopy){[string]$outCopy.Status}else{'not-started'});
                errTaskStatus=$(if($errCopy){[string]$errCopy.Status}else{'not-started'})}
            if ($null -ne $childExit) { $finished.exitCode=$childExit }
            Write-LifecycleCommandEvent $supervisorLog 'finished' $supervisorClock 0 $finished
        } catch { }
        Remove-LifecycleLaunchFiles $ChildConfig $configuration.projectRoot
    }
    exit $childExit
}
