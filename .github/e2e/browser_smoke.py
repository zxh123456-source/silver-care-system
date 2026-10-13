"""Real browser -> local static proxy -> Spring Boot -> disposable MySQL/Redis."""
import contextlib
import functools
import json
import threading
import time
import urllib.error
import urllib.request
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from playwright.sync_api import expect, sync_playwright


def run_browser(dist: Path, backend: str, password: str, evidence: Path) -> list[str]:
    class Handler(SimpleHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def proxy(self):
            body = self.rfile.read(int(self.headers.get("Content-Length", "0"))) or None
            headers = {k: v for k, v in self.headers.items()
                       if k.lower() not in ("host", "connection", "content-length", "accept-encoding")}
            request = urllib.request.Request(backend + self.path[4:], data=body, headers=headers, method=self.command)
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            try:
                response = opener.open(request, timeout=160)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                data = response.read()
                # A fresh page navigation may legitimately abandon an earlier GET response.
                with contextlib.suppress(BrokenPipeError, ConnectionAbortedError, ConnectionResetError):
                    self.send_response(response.status)
                    self.send_header("Content-Type", response.headers.get("Content-Type", "application/json"))
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)

        def do_GET(self):
            if self.path.startswith("/api/"):
                self.proxy()
            else:
                super().do_GET()

        def do_POST(self): self.proxy()
        def do_PUT(self): self.proxy()
        def do_DELETE(self): self.proxy()

    server = ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(Handler, directory=str(dist)))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    origin = f"http://127.0.0.1:{server.server_port}"
    checks, errors, contexts = [], [], []
    failed = True
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=True)
            try:
                def new_page(label):
                    context = browser.new_context(viewport={"width": 1600, "height": 1000}, timezone_id="Asia/Shanghai")
                    contexts.append((label, context))
                    context.tracing.start(screenshots=True, snapshots=True, sources=False)
                    # Observe the existing client-rendered CAPTCHA; authentication still uses the real form/API.
                    context.add_init_script(r'''window.captchaDigits=[];
                        const draw=CanvasRenderingContext2D.prototype.fillText;
                        CanvasRenderingContext2D.prototype.fillText=function(t,...a){
                        if(this.canvas.width===120 && /^\d$/.test(t))window.captchaDigits.push(t);
                        return draw.call(this,t,...a)};''')
                    page = context.new_page()
                    page.set_default_timeout(30000)
                    page.on("pageerror", lambda error: errors.append(f"{label}: {error}"))
                    return page

                def record(name):
                    checks.append(name)
                    (evidence / "checks.json").write_text(json.dumps(checks, ensure_ascii=False, indent=2), encoding="utf-8")
                    print(f"PASS: {name}", flush=True)

                def click_api(page, button, endpoint, method="POST"):
                    with page.expect_response(lambda response: endpoint in response.url
                                              and response.request.method == method, timeout=180000) as response:
                        button.click()
                    data = response.value.json()
                    assert data.get("code") == 200, (endpoint, data.get("code"), data.get("msg"))
                    return data

                def login(page, account):
                    page.goto(origin + "/#/login")
                    page.get_by_placeholder("账号", exact=True).fill(account)
                    page.get_by_placeholder("密码", exact=True).fill(password)
                    code = page.evaluate('window.captchaDigits.slice(-4).join("")')
                    assert len(code) == 4
                    page.get_by_placeholder("验证码", exact=True).fill(code)
                    click_api(page, page.get_by_role("button", name="登录", exact=True), "/api/account/login")
                    page.wait_for_url(lambda url: "/login" not in url)

                def route(page, path, title):
                    # Fresh document loads test persisted state rather than Vue keep-alive state.
                    page.goto(f"{origin}/?e2e={time.monotonic_ns()}#{path}")
                    expect(page.get_by_role("heading", name=title, exact=True)).to_be_visible()

                def choose_elder(page, scoped=False):
                    with page.expect_response(lambda response: "/api/ai/scope/elders" in response.url) as response:
                        page.get_by_role("button", name="选择", exact=True).first.click()
                    data = response.value.json()
                    assert data["code"] == 200
                    dialog = page.get_by_role("dialog", name="选择老人")
                    if scoped:
                        assert data["data"]["total"] == 1
                        expect(dialog).not_to_contain_text("E2E隔离")
                    row = dialog.locator(".el-table__body tbody tr").filter(has_text="E2E可见")
                    expect(row).to_have_count(1)  # Guard against accidentally running on a non-fixture database.
                    row.get_by_role("button", name="选择", exact=True).click()
                    page.locator(".el-popconfirm__action .el-button--warning").click()
                    expect(dialog).not_to_be_visible()

                def prompt(page, text):
                    page.locator(".el-message-box__input input").fill(text)
                    return page.locator(".el-message-box").get_by_role("button", name="确定", exact=True)

                def select_option(page, selector, text):
                    selector.click()
                    page.locator(".el-select-dropdown__item:visible").filter(
                        has=page.get_by_text(text, exact=True)).click()

                admin = new_page("admin")
                login(admin, "13547584400")
                route(admin, "/ai-care/scope", "老人数据权限")
                choose_elder(admin)
                select_option(admin, admin.locator(".toolbar .el-select"), "E2E护工")
                click_api(admin, admin.get_by_role("button", name="分配数据权限"), "/api/ai/scope/assignment")
                record("administrator login and elder assignment")

                staff = new_page("staff")
                login(staff, "18428167423")
                route(staff, "/ai-care/health", "健康趋势观察")
                choose_elder(staff, scoped=True)
                record("staff login and elder selector isolation")
                temperature = staff.locator(".el-form-item").filter(
                    has=staff.locator("label").filter(has_text="体温")).get_by_role("spinbutton")
                for value in ("36.0", "38.2"):
                    temperature.fill(value)
                    click_api(staff, staff.get_by_role("button", name="保存测量"), "/api/ai/health/measurement")
                route(staff, "/ai-care/daily", "每日护理助手")
                alert = staff.locator(".task-center").first.locator(".el-table__body tbody tr").filter(has_text="健康变化").first
                click_api(staff, alert.get_by_role("button", name="确认", exact=True), "/api/ai/daily/alerts/ack")
                alert.get_by_role("button", name="解除", exact=True).click()
                click_api(staff, prompt(staff, "E2E测量复核"), "/api/ai/daily/alerts/resolve")
                select_option(staff, staff.locator(".task-center").first.locator(".el-select"), "已解除")
                expect(staff.locator(".task-center").first).to_contain_text("E2E测量复核")
                record("health measurements and persisted alert acknowledgement/resolution")

                synced = click_api(staff, staff.get_by_role("button", name="全量同步所选日期事项"), "/api/ai/daily/tasks/sync")
                assert synced["data"] >= 201
                tasks = staff.locator(".task-center").nth(1)
                expect(tasks.locator(".el-table__body tbody tr")).to_have_count(200)
                task = tasks.locator(".el-table__body tbody tr").filter(has_text="E2E交接任务")
                click_api(staff, task.get_by_role("button", name="认领", exact=True), "/api/ai/daily/tasks/claim")
                task.get_by_role("button", name="转交", exact=True).click()
                dialog = staff.get_by_role("dialog", name="转交协作任务")
                select_option(staff, dialog.locator(".el-select"), "超管")
                click_api(staff, dialog.get_by_role("button", name="确认转交"), "/api/ai/daily/tasks/transfer")
                record("full source sync above 200, task claim and transfer")

                route(admin, "/ai-care/daily", "每日护理助手")
                tasks = admin.locator(".task-center").nth(1)
                task = tasks.locator(".el-table__body tbody tr").filter(has_text="E2E交接任务")
                expect(task).to_contain_text("超管")
                task.get_by_role("button", name="复核完成").click()
                click_api(admin, prompt(admin, "E2E人工复核"), "/api/ai/daily/tasks/complete")
                select_option(admin, tasks.locator(".el-select"), "已完成")
                expect(tasks).to_contain_text("E2E人工复核")
                click_api(admin, admin.get_by_role("button", name="全量同步所选日期事项"), "/api/ai/daily/tasks/sync")
                expect(tasks).to_contain_text("E2E人工复核")
                record("administrator completion survives full resync")

                route(staff, "/ai-care/medication", "用药执行核对")
                choose_elder(staff, scoped=True)
                staff.locator(".el-form-item").filter(has=staff.locator("label").filter(
                    has_text="药品名称")).get_by_role("textbox").fill("E2E测试计划")
                staff.get_by_placeholder("例如：每次1片，餐后执行").fill("E2E人工核对")
                select_option(staff, staff.locator(".el-form-item").filter(
                    has=staff.locator("label").filter(has_text="执行时段")).locator(".el-select"), "早")
                staff.get_by_role("heading", name="用药执行核对").click()
                click_api(staff, staff.get_by_role("button", name="保存计划"), "/api/ai/medication/plan")
                med = staff.locator(".el-table__body tbody tr").filter(has_text="E2E测试计划")
                med.get_by_role("button", name="未执行", exact=True).click()
                click_api(staff, prompt(staff, "E2E测试未执行原因"), "/api/ai/medication/execute")
                route(staff, "/ai-care/daily", "每日护理助手")
                alerts = staff.locator(".task-center").first
                expect(alerts).to_contain_text("E2E测试计划")
                expect(alerts).to_contain_text("已登记未执行")
                record("medication plan and skipped execution persist as alert")
                route(staff, "/ai-care/medication", "用药执行核对")
                choose_elder(staff, scoped=True)
                click_api(staff, staff.locator(".el-table__body tbody tr").filter(
                    has_text="E2E测试计划").get_by_role("button", name="已执行", exact=True), "/api/ai/medication/execute")
                route(staff, "/ai-care/daily", "每日护理助手")
                expect(staff.locator(".task-center").first).not_to_contain_text("E2E测试计划")
                record("completed medication execution resolves its alert")

                route(admin, "/ai-care/scope", "老人数据权限")
                row = admin.locator(".el-table__body tbody tr").filter(has_text="E2E护工")
                row.get_by_role("button", name="撤销", exact=True).click()
                click_api(admin, admin.locator(".el-message-box").get_by_role("button", name="确定", exact=True),
                          "/api/ai/scope/assignment", method="DELETE")
                route(staff, "/ai-care/health", "健康趋势观察")
                with staff.expect_response(lambda response: "/api/ai/scope/elders" in response.url) as response:
                    staff.get_by_role("button", name="选择", exact=True).first.click()
                assert response.value.json()["data"]["total"] == 0
                expect(staff.get_by_role("dialog", name="选择老人").locator(".el-table__body tbody tr")).to_have_count(0)
                record("elder permission revocation takes effect without relogin")
                assert not errors, errors
                admin.screenshot(path=str(evidence / "admin.png"))
                staff.screenshot(path=str(evidence / "staff.png"))
                record("no browser JavaScript exceptions")
                failed = False
                return checks
            finally:
                for label, context in contexts:
                    with contextlib.suppress(Exception):
                        if failed:
                            context.tracing.stop(path=str(evidence / f"{label}-failure-trace.zip"))
                            for page in context.pages:
                                page.screenshot(path=str(evidence / f"{label}-failure.png"))
                        else:
                            context.tracing.stop()
                browser.close()
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
