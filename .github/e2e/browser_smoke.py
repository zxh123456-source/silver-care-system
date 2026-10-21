"""Real browser -> local static proxy -> Spring Boot -> disposable MySQL/Redis."""
import contextlib
import io
import base64
import functools
import json
import re
import threading
import time
import urllib.error
import urllib.request
import urllib.parse
import zipfile
from datetime import datetime, timedelta
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from playwright.sync_api import expect, sync_playwright


def run_browser(dist: Path, backend: str, password: str, evidence: Path) -> list[str]:
    entry_script = re.search(r'<script\b[^>]*\bsrc="([^"]+)"', (dist / "index.html").read_text(encoding="utf-8"))
    assert entry_script, "Built frontend entry script is missing"
    entry_path = urllib.parse.urlparse(entry_script.group(1)).path

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
                    for header in ("Content-Disposition", "Content-Security-Policy", "X-Content-Type-Options", "Cache-Control", "Pragma"):
                        if response.headers.get(header): self.send_header(header, response.headers[header])
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
    expected_console_errors = []
    expired_contexts, revoked_contexts = set(), set()
    failed = True
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=True)
            try:
                def allow_console_once(label, path_pattern, message_pattern):
                    expected_console_errors.append((label, path_pattern, message_pattern))

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
                    def console_error(message):
                        if message.type != "error":
                            return
                        text = message.text
                        path = urllib.parse.urlparse(message.location.get("url", "")).path
                        for index, (expected_label, expected_path, expected_message) in enumerate(expected_console_errors):
                            if label == expected_label and re.fullmatch(expected_path, path) and re.fullmatch(expected_message, text):
                                expected_console_errors.pop(index)
                                return
                        expected_auth_paths = {
                            "/api/account/login", "/api/ai/daily/tasks", "/api/ai/daily/alerts",
                            "/api/ai/daily/notifications", "/api/ai/daily/notification-count",
                            "/api/ai/daily/scan-status", "/api/ai/daily/overview", "/api/ai/scope/elders",
                        }
                        if re.fullmatch(r"Failed to load resource: the server responded with a status of 401 \(.*\)", text):
                            if path == "/api/account/login" or (label in expired_contexts and
                                    (path in expected_auth_paths or re.fullmatch(r"/api/file/[0-9]+/content", path))):
                                return
                        if label in revoked_contexts and re.fullmatch(r"Failed to load resource: the server responded with a status of 403 \(.*\)", text) and path == "/api/ai/health/measurement":
                            return
                        errors.append(f"{label} console.error: {text}")
                    page.on("console", console_error)
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

                def login(page, account, credential=None):
                    page.goto(origin + "/#/login")
                    # Element Plus loading buttons can have no disabled attribute while clicks are ignored.
                    expect(page.get_by_role("button", name="登录", exact=True)).not_to_have_class(re.compile(r"\bis-loading\b"))
                    page.get_by_placeholder("账号", exact=True).fill(account)
                    page.get_by_placeholder("密码", exact=True).fill(password if credential is None else credential)
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

                mobile_login = new_page("mobile-login")
                for width in (390, 320):
                    mobile_login.set_viewport_size({"width": width, "height": 844})
                    mobile_login.goto(origin + "/#/login")
                    for placeholder in ("账号", "密码", "验证码"):
                        field = mobile_login.get_by_placeholder(placeholder, exact=True)
                        expect(field).to_be_visible()
                        assert field.bounding_box()["width"] >= 40, (width, placeholder)
                        field.fill("123456")
                        expect(field).to_have_value("123456")
                    mobile_login.get_by_role("button", name="忘记密码", exact=True).click()
                    for placeholder in ("已登记邮箱", "新密码", "确认密码", "验证码"):
                        field = mobile_login.get_by_placeholder(placeholder, exact=True)
                        expect(field).to_be_visible()
                        assert field.bounding_box()["width"] >= 40, (width, placeholder)
                    assert mobile_login.evaluate("document.documentElement.scrollWidth <= innerWidth")
                    mobile_login.get_by_role("button", name="返回登录", exact=True).click()
                record("login and password reset inputs remain usable at 390px and 320px")

                admin = new_page("admin")
                admin.goto(origin + "/#/login")
                admin.get_by_placeholder("账号", exact=True).fill("13547584400")
                admin.get_by_placeholder("密码", exact=True).fill(password + "incorrect")
                admin.get_by_placeholder("验证码", exact=True).fill(admin.evaluate('window.captchaDigits.slice(-4).join("")'))
                with admin.expect_response(lambda response: "/api/account/login" in response.url) as rejected:
                    admin.get_by_role("button", name="登录", exact=True).click()
                assert rejected.value.status == 401
                assert rejected.value.json()["code"] == 401
                expect(admin.get_by_role("button", name="登录", exact=True)).to_be_enabled()
                expect(admin.get_by_role("button", name="登录", exact=True)).not_to_have_class(re.compile(r"\bis-loading\b"))
                record("incorrect credentials return 401 and login remains usable")
                login(admin, "13547584400")
                expect(admin.locator("#myEcharts canvas")).to_have_count(1)
                home_hash = admin.evaluate("window.location.hash")
                route(admin, "/ai-care/daily", "每日护理助手")
                held_trends = []
                def hold_trend(request_route):
                    held_trends.append((request_route, request_route.fetch()))
                admin.route("**/api/home/businessTrend", hold_trend)
                admin.evaluate("hash => { window.location.hash = hash }", home_hash)
                expect(admin.locator("#myEcharts")).to_be_visible()
                deadline = time.monotonic() + 10
                while not held_trends and time.monotonic() < deadline:
                    admin.wait_for_timeout(20)
                assert len(held_trends) == 1
                admin.evaluate("window.location.hash = '#/ai-care/daily'")
                expect(admin.get_by_role("heading", name="每日护理助手", exact=True)).to_be_visible()
                for request_route, response in held_trends:
                    request_route.fulfill(response=response)
                admin.unroute("**/api/home/businessTrend", hold_trend)
                admin.wait_for_timeout(150)
                assert not errors, errors
                admin.evaluate("hash => { window.location.hash = hash }", home_hash)
                expect(admin.locator("#myEcharts canvas")).to_have_count(1)
                record("a delayed real trend response cannot touch an unmounted chart and home can remount")
                # No scan request: the production scheduler must recover yesterday's missing record.
                route(admin, "/ai-care/daily", "每日护理助手")
                notices = admin.locator(".notification-center")
                automatic = notices.locator(".el-table__body tbody tr").filter(has_text="E2E自动核对计划")
                expect(automatic).to_have_count(1, timeout=30000)
                expect(admin.locator(".care-notification-button .el-badge__content")).to_have_text("1")
                expect(automatic).to_contain_text("不能据此认定漏服")
                click_api(admin, automatic.get_by_role("button", name="标记已读", exact=True), "/api/ai/daily/notifications/read")
                expect(automatic).to_have_count(0)
                expect(admin.locator(".care-notification-button .el-badge__content")).not_to_be_visible()
                automatic_alert = admin.locator(".task-center:not(.notification-center)").first.locator(".el-table__body tbody tr").filter(has_text="E2E自动核对计划")
                expect(automatic_alert).to_contain_text("待确认")
                route(admin, "/ai-care/daily", "每日护理助手")
                expect(automatic).to_have_count(0)
                expect(automatic_alert).to_contain_text("待确认")
                record("startup compensation produces an unread notice and personal read persists without acknowledging it")
                admin.goto(origin + "/#/people/staff")
                employee = admin.locator(".el-table__body tbody tr").filter(has_text="E2E护工")
                expect(employee).to_have_count(1)
                # The original seed avatar is an intentionally unresolved legacy reference.
                allow_console_once("admin", r"/api/file/resolve", r"Failed to load resource: the server responded with a status of 403 \(.*\)")
                employee.get_by_role("button", name="编辑", exact=True).click()
                editor = admin.get_by_role("dialog", name="编辑", exact=True)
                expect(editor).to_be_visible()
                image_reads = []
                admin.on("response", lambda response: image_reads.append(response)
                         if re.search(r"/api/file/[0-9]+/content$", response.url) else None)
                png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEUlEQVR4nGP4z8DA8B+MgBgAHfAD/dPQfSYAAAAASUVORK5CYII=")
                with admin.expect_response(lambda response: "/api/file/uploadImg" in response.url) as uploaded:
                    editor.locator("input[type=file]").set_input_files({"name": "care.png", "mimeType": "image/png", "buffer": png})
                image_result = uploaded.value.json()
                assert image_result["code"] == 200, image_result
                image_url = image_result["data"]["url"]
                expect(editor.locator(".el-upload-list__item")).to_have_count(1)
                expect(editor.locator(".el-upload-list__item img")).to_have_attribute("src", re.compile(r"^blob:"))
                admin.wait_for_function("() => { const i=document.querySelector('[role=dialog] .el-upload-list__item img'); return i && i.complete && i.naturalWidth===2; }")
                preview_url = editor.locator(".el-upload-list__item img").get_attribute("src")
                fetched = [response for response in image_reads if response.url == origin + image_url]
                assert fetched and fetched[-1].status == 200
                assert fetched[-1].request.headers.get("token")
                assert "token=" not in fetched[-1].url and "no-store" in fetched[-1].headers.get("cache-control", "")
                assert admin.request.get(origin + image_url).status == 401
                # Element Plus reports this known upload rejection from the built entry bundle.
                allow_console_once("admin", re.escape(entry_path), r"Error: 图片内容无效(?:\n[^\n]*)+")
                with admin.expect_response(lambda response: "/api/file/uploadImg" in response.url) as invalid_image:
                    editor.locator("input[type=file]").set_input_files({"name": "forged.png", "mimeType": "image/png", "buffer": b"<html>not a picture</html>"})
                assert invalid_image.value.json()["code"] == 400
                expect(admin.get_by_text("图片内容无效", exact=True)).to_be_visible()
                expect(editor.locator(".el-upload-list__item")).to_have_count(1)
                expect(editor.locator(".el-upload-list__item img")).to_have_attribute("src", preview_url)
                record("real image upload renders and a forged replacement preserves the saved image")
                with admin.expect_request(lambda request: "/api/staff/editStaff" in request.url) as saved_request:
                    click_api(admin, editor.get_by_role("button", name="提交", exact=True), "/api/staff/editStaff", "PUT")
                assert saved_request.value.post_data_json["avator"] == image_url
                expect(editor).not_to_be_visible()
                employee.get_by_role("button", name="编辑", exact=True).click()
                expect(editor.locator(".el-upload-list__item img")).to_have_attribute("src", re.compile(r"^blob:"))
                admin.wait_for_function("() => { const i=document.querySelector('[role=dialog] .el-upload-list__item img'); return i && i.complete && i.naturalWidth===2; }")
                editor.locator(".el-upload-list__item").hover()
                editor.get_by_role("button", name="移除图片", exact=True).click()
                expect(editor.locator(".el-upload-list__item")).to_have_count(0)
                editor.get_by_role("button", name="提交", exact=True).click()
                expect(editor.get_by_text("请选择上传员工头像", exact=True)).to_be_visible()
                editor.get_by_role("button", name="取消", exact=True).click()
                record("uploaded avatar persists and removing it clears the form value")
                admin.goto(origin + "/#/people/old")
                with admin.expect_download() as downloaded, admin.expect_response(lambda response: "/api/elderRecord/exportExcel" in response.url) as exported:
                    admin.get_by_role("button", name="导出", exact=True).click()
                export_response = exported.value
                assert export_response.status == 200 and export_response.request.headers.get("token")
                assert "spreadsheetml.sheet" in export_response.headers.get("content-type", "")
                assert "no-store" in export_response.headers.get("cache-control", "")
                with zipfile.ZipFile(io.BytesIO(export_response.body())) as workbook:
                    assert "xl/workbook.xml" in workbook.namelist()
                    assert "E2E可见" in workbook.read("xl/sharedStrings.xml").decode("utf-8")
                assert downloaded.value.suggested_filename.endswith(".xlsx")
                downloaded.value.save_as(str(evidence / "elder-export.xlsx"))
                record("authenticated workbook downloads directly without a public file URL")

                # Drive the actual booking and admission forms against the disposable database.
                admin.goto(f"{origin}/?bed={time.monotonic_ns()}#/sale/book")
                admin.get_by_role("button", name="新增", exact=True).click()
                bed_booking = admin.get_by_role("dialog", name="新增", exact=True)
                expect(bed_booking).to_be_visible()
                bed_booking.locator(".el-form-item").filter(has_text="老人姓名").get_by_role("button").click()
                elder_picker = admin.get_by_role("dialog", name="选择老人", exact=True)
                elder_picker.locator(".el-form-item").filter(has_text="姓名").locator("input").fill("E2E预订")
                click_api(admin, elder_picker.get_by_role("button", name="搜索", exact=True), "/api/reserve/pageSearchElderByKey", "GET")
                elder_picker.locator(".el-table__body tbody tr").filter(has_text="E2E预订").get_by_role("button", name="选择", exact=True).click()
                admin.locator(".el-popconfirm__action .el-button--warning").click()
                expect(elder_picker).not_to_be_visible()
                with admin.expect_response(lambda response: "/api/reserve/getBuildTree" in response.url) as tree_response:
                    bed_booking.locator(".el-form-item").filter(has_text="预定床位").locator("input").click()
                tree = tree_response.value.json()
                assert tree["code"] == 200

                def bed_path(nodes, bed_id):
                    for node in nodes or []:
                        if node.get("level") == 4 and node["id"] == bed_id:
                            return [node["name"]]
                        child = bed_path(node.get("childrenList"), bed_id)
                        if child: return [node["name"], *child]
                    return None

                path = bed_path(tree["data"], 1)
                assert path and len(path) == 4
                bed_picker = admin.get_by_role("dialog", name="选择床位", exact=True)
                for label in path:
                    bed_picker.locator(".el-tree-node__label").get_by_text(label, exact=True).click()
                expect(bed_picker).not_to_be_visible()
                bed_booking.locator(".el-form-item").filter(has_text="预付定金").locator("input").fill("100")
                bed_booking.locator(".el-form-item").filter(has_text="交款人姓名").locator("input").fill("测试亲属")
                bed_booking.locator(".el-form-item").filter(has_text="交款人电话").locator("input").fill("19900003002")
                due = bed_booking.locator(".el-form-item").filter(has_text="预定到期日期").locator("input")
                due.fill((datetime.now() + timedelta(days=2)).strftime("%Y-%m-%d 23:00:00"))
                due.press("Enter")
                sales = bed_booking.locator(".el-form-item").filter(has_text="营销人员").locator(".el-select")
                sales.click()
                admin.locator(".el-select-dropdown__item:visible").first.click()
                click_api(admin, bed_booking.get_by_role("button", name="提交", exact=True), "/api/reserve/addReserve")
                expect(bed_booking).not_to_be_visible()
                bed_row = admin.locator(".el-table__body tbody tr").filter(has_text="E2E预订")
                expect(bed_row).to_contain_text("有效预订")
                expect(bed_row.get_by_role("button", name="退款", exact=True)).to_be_visible()
                record("reservation form creates an active booking on the selected free bed")

                admin.goto(f"{origin}/?bed={time.monotonic_ns()}#/live/enter")
                with admin.expect_response(lambda response: "/api/checkContract/listReserveStaff" in response.url) as admission_staff:
                    admin.get_by_role("button", name="新增", exact=True).click()
                admission = admin.get_by_role("dialog", name="新增", exact=True)
                expect(admission).to_be_visible()
                admission.locator(".el-form-item").filter(has_text="老人姓名").get_by_role("button").click()
                elder_picker.locator(".el-form-item").filter(has_text="姓名").locator("input").fill("E2E预订")
                click_api(admin, elder_picker.get_by_role("button", name="搜索", exact=True), "/api/checkContract/pageSearchElderByKey", "GET")
                elder_picker.locator(".el-table__body tbody tr").filter(has_text="E2E预订").get_by_role("button", name="选择", exact=True).click()
                admin.locator(".el-popconfirm__action .el-button--warning").click()
                expect(elder_picker).not_to_be_visible()
                expect(admission.locator(".el-form-item").filter(has_text="入住床位").locator("input")).to_have_value(path[-1])
                for field, choice in [("护理等级", "一级护理"), ("餐饮套餐", "颐养套餐")]:
                    select_option(admin, admission.locator(".el-form-item").filter(has_text=field).locator(".el-select"), choice)
                select_option(admin, admission.locator(".el-form-item").filter(has_text="营销人员").locator(".el-select"),
                              admission_staff.value.json()["data"][0]["name"])
                signed = admission.locator(".el-form-item").filter(has_text="合同签订日期").locator("input")
                signed.fill(datetime.now().strftime("%Y-%m-%d 00:00:00"))
                signed.press("Enter")
                period = admission.locator(".el-form-item").filter(has_text="合同有效期限").locator("input")
                period.nth(0).fill(datetime.now().strftime("%Y-%m-%d"))
                period.nth(1).fill((datetime.now() + timedelta(days=365)).strftime("%Y-%m-%d"))
                period.nth(1).press("Enter")
                contacts = admission.locator(".el-form-item").filter(has_text="紧急联系人")
                contacts.get_by_role("button").first.click()
                for placeholder, value in [("请输入姓名", "测试亲属"), ("请输入电话", "19900003002"),
                                           ("请输入邮箱", "bed-e2e@example.com"), ("请输入关系", "亲属")]:
                    contacts.get_by_placeholder(placeholder, exact=True).fill(value)
                contacts.get_by_text("否", exact=True).click()
                expect(contacts.get_by_role("radio", name="否", exact=True)).to_be_checked()
                click_api(admin, admission.get_by_role("button", name="提交", exact=True), "/api/checkContract/addCheckContract")
                expect(admission).not_to_be_visible()
                admitted = admin.locator(".el-table__body tbody tr").filter(has_text="E2E预订")
                expect(admitted).to_contain_text("入住")
                expect(admitted.get_by_role("button", name="删除", exact=True)).to_have_count(0)
                record("own reservation converts to admission on the same bed through the real form")

                admin.goto(f"{origin}/?bed={time.monotonic_ns()}#/sale/book")
                expect(bed_row).to_contain_text("已转入住")
                expect(bed_row).to_contain_text(path[-1])
                expect(bed_row.get_by_role("button", name="退款", exact=True)).to_have_count(0)
                bed_row.get_by_role("button", name="查看", exact=True).click()
                history = admin.get_by_role("dialog", name="查看", exact=True)
                expect(history.locator(".el-form-item").filter(has_text="预定床位").locator("input")).to_have_value(path[-1])
                expect(history.get_by_role("button", name="提交", exact=True)).to_have_count(0)
                history.locator(".el-dialog__headerbtn").click()
                record("converted reservation retains its bed history and cannot be refunded")
                admin.goto(origin + "/#/charge/prestore")
                account = admin.locator(".el-table__body tbody tr").filter(has_text="E2E可见")
                expect(account).to_have_count(1)
                expect(account.locator("td").nth(5)).to_have_text(re.compile(r"^\s*100(?:\.0{1,2})?\s*$"))
                account.get_by_role("button", name="账户充值", exact=True).click()
                payment = admin.get_by_role("dialog", name="账户充值", exact=True)
                payment.locator(".el-input__inner").fill("10.00")
                credits = []
                admin.on("request", lambda request: credits.append(request) if "/api/depositRecharge/recharge" in request.url else None)
                with admin.expect_response(lambda response: "/api/depositRecharge/recharge" in response.url) as credited:
                    payment.get_by_role("button", name="提交", exact=True).evaluate("button => { button.click(); button.click(); }")
                assert credited.value.json()["code"] == 200
                expect(payment).not_to_be_visible()
                expect(account.locator("td").nth(5)).to_have_text(re.compile(r"^\s*110(?:\.0{1,2})?\s*$"))
                assert len(credits) == 1
                record("recharge double-click submits once and the refreshed balance includes the credit")

                admin.goto(origin + "/#/serve/book")
                admin.get_by_role("button", name="新增", exact=True).click()
                booking = admin.get_by_role("dialog", name="新增", exact=True)
                booking.locator(".el-form-item").filter(has_text="老人姓名").get_by_role("button").click()
                picker = admin.get_by_role("dialog", name="选择老人", exact=True)
                picker.locator(".el-table__body tbody tr").filter(has_text="E2E可见").get_by_role("button", name="选择", exact=True).click()
                admin.locator(".el-popconfirm__action .el-button--warning").click()
                expect(picker).not_to_be_visible()
                select_option(admin, booking.locator(".el-select"), "E2E计费服务")
                expect(booking.locator(".el-form-item").filter(has_text="服务单价").locator("input")).to_have_value(re.compile(r"12\.50?"))
                booking.locator(".el-form-item").filter(has_text="服务次数").locator("input").fill("2")
                click_api(admin, booking.get_by_role("button", name="提交", exact=True), "/api/nurseReserve/addNurseReserve")
                expect(booking).not_to_be_visible()
                reservation = admin.locator(".el-table__body tbody tr").filter(has_text="E2E计费服务")
                expect(reservation).to_have_count(1)
                expect(reservation.locator("td").nth(7)).to_have_text(re.compile(r"^\s*25(?:\.0{1,2})?\s*$"))
                reservation.get_by_role("button", name="执行", exact=True).click()
                execution = admin.get_by_role("dialog", name="执行", exact=True)
                select_option(admin, execution.locator(".el-select"), "E2E护工")
                execution.locator(".el-date-editor input").fill("2026-10-18 08:00:00")
                execution.locator(".el-date-editor input").press("Enter")
                click_api(admin, execution.get_by_role("button", name="提交", exact=True), "/api/nurseReserve/executeNurseReserve", "PUT")
                expect(execution).not_to_be_visible()
                expect(reservation).to_contain_text("已完成")
                expect(reservation.get_by_role("button", name="执行", exact=True)).not_to_be_visible()
                admin.goto(origin + "/#/charge/prestore")
                expect(admin.locator(".el-table__body tbody tr").filter(has_text="E2E可见").locator("td").nth(5)).to_have_text(re.compile(r"^\s*85(?:\.0{1,2})?\s*$"))
                record("catalog-priced nursing reservation completes and deducts the exact charge")

                # The real server commits; only its response to this browser is lost.
                # Refresh must restore the original business key, not send a new credit.
                account.get_by_role("button", name="账户充值", exact=True).click()
                payment.locator(".el-input__inner").fill("7.00")
                lost_requests, lost_receipts = [], []

                def lose_committed_recharge(intercept):
                    lost_requests.append(intercept.request.post_data_json)
                    committed = intercept.fetch()
                    receipt = committed.json()
                    assert committed.status == 200 and receipt["code"] == 200, receipt
                    lost_receipts.append(receipt["data"])
                    allow_console_once("admin", r"/api/depositRecharge/recharge", r"Failed to load resource: net::ERR_FAILED")
                    intercept.abort("failed")

                recharge_pattern = "**/api/depositRecharge/recharge"
                admin.route(recharge_pattern, lose_committed_recharge, times=1)
                with admin.expect_event("requestfailed", predicate=lambda request: "/api/depositRecharge/recharge" in request.url):
                    payment.get_by_role("button", name="提交", exact=True).click()
                expect(payment.get_by_role("button", name="提交", exact=True)).to_be_enabled()
                assert len(lost_requests) == len(lost_receipts) == 1
                original = lost_requests[0]
                pending_key = f'silver-care:recharge-intent:v1:1:{original["elderId"]}'
                assert admin.evaluate("key => JSON.parse(sessionStorage.getItem(key))", pending_key) == original
                admin.reload()
                expect(account.locator("td").nth(5)).to_have_text(re.compile(r"^\s*92(?:\.0{1,2})?\s*$"))
                account.get_by_role("button", name="账户充值", exact=True).click()
                expect(payment.locator(".el-input__inner")).to_have_value("7.00")
                expect(payment).to_contain_text("待确认充值")
                with admin.expect_request(lambda request: "/api/depositRecharge/recharge" in request.url) as replay:
                    receipt = click_api(admin, payment.get_by_role("button", name="提交", exact=True), "/api/depositRecharge/recharge", "PUT")
                assert replay.value.post_data_json == original
                assert receipt["data"] == lost_receipts[0]
                expect(payment).not_to_be_visible()
                expect(account.locator("td").nth(5)).to_have_text(re.compile(r"^\s*92(?:\.0{1,2})?\s*$"))
                assert admin.evaluate("key => sessionStorage.getItem(key)", pending_key) is None
                with admin.expect_response(lambda response: "/api/depositRecharge/pageRechargeLedger" in response.url) as ledger_result:
                    account.get_by_role("button", name="充值记录", exact=True).click()
                ledger = ledger_result.value.json()
                assert ledger["code"] == 200 and ledger["data"]["total"] == 2, ledger
                assert sum(row["requestId"] == original["requestId"] for row in ledger["data"]["list"]) == 1
                ledger_dialog = admin.get_by_role("dialog", name="充值记录", exact=True)
                expect(ledger_dialog.locator(".el-table__body tbody tr")).to_have_count(2)
                ledger_dialog.locator(".el-dialog__headerbtn").click()
                record("lost recharge response survives refresh and replays one immutable ledger receipt")

                # A failed persistence write must stop the HTTP request before any money is moved.
                account.get_by_role("button", name="账户充值", exact=True).click()
                payment.locator(".el-input__inner").fill("8.00")
                credit_count = len(credits)
                admin.evaluate('''() => {
                    window.originalStorageSetItem = Storage.prototype.setItem;
                    Storage.prototype.setItem = function(key, value) {
                        if (key.startsWith("silver-care:recharge-intent:")) throw new DOMException("fixture quota", "QuotaExceededError");
                        return window.originalStorageSetItem.call(this, key, value);
                    };
                }''')
                try:
                    payment.get_by_role("button", name="提交", exact=True).click()
                    expect(payment).to_contain_text("因此尚未提交")
                    expect(payment.get_by_role("button", name="提交", exact=True)).to_be_disabled()
                    assert len(credits) == credit_count
                finally:
                    admin.evaluate("() => { Storage.prototype.setItem = window.originalStorageSetItem; delete window.originalStorageSetItem; }")
                payment.get_by_role("button", name="取消", exact=True).click()
                record("recharge storage failure prevents a request and preserves the balance")
                route(admin, "/ai-care/scope", "老人数据权限")
                choose_elder(admin)
                select_option(admin, admin.locator(".toolbar .el-select"), "E2E护工")
                click_api(admin, admin.get_by_role("button", name="分配数据权限"), "/api/ai/scope/assignment")
                record("administrator login and elder assignment")

                staff = new_page("staff")
                login(staff, "18428167423")
                expect(staff.locator('img[alt="头像"]')).to_have_attribute("src", re.compile(r"^blob:"))
                # Model a different account's pending receipt remaining in the same browser tab.
                # A staff session must use its actual userid (4), not the legacy fixed app.uid (1).
                staff.evaluate("([key, value]) => sessionStorage.setItem(key, value)", [pending_key, json.dumps(original)])
                staff.goto(origin + "/#/charge/prestore")
                staff.locator(".el-table__body tbody tr").filter(has_text="E2E可见").get_by_role("button", name="账户充值", exact=True).click()
                staff_payment = staff.get_by_role("dialog", name="账户充值", exact=True)
                expect(staff_payment.locator(".el-input__inner")).to_have_value("")
                assert staff.evaluate("key => JSON.parse(sessionStorage.getItem(key))", pending_key) == original
                staff_payment.get_by_role("button", name="取消", exact=True).click()
                staff.evaluate("key => sessionStorage.removeItem(key)", pending_key)
                record("another account's pending recharge is neither restored nor cleared")

                # localStorage is shared by tabs, while each Vuex instance retains its own identity.
                # Switching to an administrator elsewhere must invalidate this staff member's form.
                staff.locator(".el-table__body tbody tr").filter(has_text="E2E可见").get_by_role("button", name="账户充值", exact=True).click()
                staff_payment.locator(".el-input__inner").fill("3.00")
                cross_account_requests = []
                staff.on("request", lambda request: cross_account_requests.append(request.post_data_json)
                         if "/api/depositRecharge/recharge" in request.url else None)
                other_tab = staff.context.new_page()
                other_tab.set_default_timeout(30000)
                login(other_tab, "13547584400")
                expect(staff.locator('img[alt="头像"]')).to_have_count(0)
                # Dispatch any already queued click without racing the closing animation.
                # If the form has closed there are no buttons; if it remains, its handler must still refuse.
                staff_payment.get_by_role("button", name="提交", exact=True).evaluate_all(
                    "buttons => buttons.forEach(button => button.click())")
                staff.wait_for_function('''() => {
                    const dialog = [...document.querySelectorAll('[role="dialog"]')].find(node => node.getAttribute('aria-label') === '账户充值');
                    return !dialog || !dialog.getClientRects().length || document.body.innerText.includes('充值回执与本次充值不一致');
                }''')
                assert not cross_account_requests, "Old tab submitted a recharge using another account: " + json.dumps(cross_account_requests)
                expect(staff_payment).not_to_be_visible()
                other_tab.close()
                # The new administrator login superseded the earlier token; restore both real sessions.
                login(admin, "13547584400")
                login(staff, "18428167423")
                record("switching accounts in another tab invalidates the old recharge form before sending")
                record("an account switch clears the previous authenticated avatar preview")

                staff.goto(origin + "/#/charge/prestore")
                other_tab = staff.context.new_page()
                other_tab.set_default_timeout(30000)
                delayed_ledger = []

                def deliver_old_ledger_after_login_changes(intercept):
                    old_response = intercept.fetch()
                    old_data = old_response.json()
                    assert old_data["code"] == 200 and old_data["data"]["total"] == 2, old_data
                    delayed_ledger.append(old_data)
                    login(other_tab, "13547584400")
                    intercept.fulfill(response=old_response)

                staff.route("**/api/depositRecharge/pageRechargeLedger?*", deliver_old_ledger_after_login_changes, times=1)
                with staff.expect_response(lambda response: "/api/depositRecharge/pageRechargeLedger" in response.url):
                    staff.locator(".el-table__body tbody tr").filter(has_text="E2E可见").get_by_role("button", name="充值记录", exact=True).click()
                assert len(delayed_ledger) == 1
                expect(staff.get_by_role("dialog", name="充值记录", exact=True)).not_to_be_visible()
                other_tab.close()
                login(admin, "13547584400")
                login(staff, "18428167423")
                record("a delayed ledger response cannot reopen or populate a dialog after an account switch")
                route(staff, "/ai-care/health", "健康趋势观察")
                choose_elder(staff, scoped=True)
                record("staff login and elder selector isolation")
                temperature = staff.locator(".el-form-item").filter(
                    has=staff.locator("label").filter(has_text="体温")).get_by_role("spinbutton")
                for value in ("36.0", "38.2"):
                    temperature.fill(value)
                    click_api(staff, staff.get_by_role("button", name="保存测量"), "/api/ai/health/measurement")
                route(staff, "/ai-care/daily", "每日护理助手")
                personal_notices = staff.locator(".notification-center")
                expect(personal_notices).not_to_contain_text("E2E自动核对计划")
                health_notice = personal_notices.locator(".el-table__body tbody tr").filter(has_text="健康变化").first
                expect(health_notice).to_be_visible()
                click_api(staff, health_notice.get_by_role("button", name="标记已读", exact=True), "/api/ai/daily/notifications/read")
                expect(personal_notices.locator(".el-table__body tbody tr").filter(has_text="健康变化")).to_have_count(0)
                record("staff notices follow elder scope and reading preserves the open health alert")
                alert = staff.locator(".task-center:not(.notification-center)").first.locator(".el-table__body tbody tr").filter(has_text="健康变化").first
                click_api(staff, alert.get_by_role("button", name="确认", exact=True), "/api/ai/daily/alerts/ack")
                alert.get_by_role("button", name="解除", exact=True).click()
                click_api(staff, prompt(staff, "E2E测量复核"), "/api/ai/daily/alerts/resolve")
                select_option(staff, staff.locator(".task-center:not(.notification-center)").first.locator(".el-select"), "已解除")
                expect(staff.locator(".task-center:not(.notification-center)").first).to_contain_text("E2E测量复核")
                record("health measurements and persisted alert acknowledgement/resolution")

                synced = click_api(staff, staff.get_by_role("button", name="全量同步所选日期事项"), "/api/ai/daily/tasks/sync")
                assert synced["data"] >= 201
                tasks = staff.locator(".task-center:not(.notification-center)").nth(1)
                expect(tasks.locator(".el-table__body tbody tr")).to_have_count(200)
                task = tasks.locator(".el-table__body tbody tr").filter(has_text="E2E交接任务")
                click_api(staff, task.get_by_role("button", name="认领", exact=True), "/api/ai/daily/tasks/claim")
                task.get_by_role("button", name="转交", exact=True).click()
                dialog = staff.get_by_role("dialog", name="转交协作任务")
                select_option(staff, dialog.locator(".el-select"), "超管")
                click_api(staff, dialog.get_by_role("button", name="确认转交"), "/api/ai/daily/tasks/transfer")
                record("full source sync above 200, task claim and transfer")

                route(admin, "/ai-care/daily", "每日护理助手")
                tasks = admin.locator(".task-center:not(.notification-center)").nth(1)
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
                alerts = staff.locator(".task-center:not(.notification-center)").first
                expect(alerts).to_contain_text("E2E测试计划")
                expect(alerts).to_contain_text("已登记未执行")
                record("medication plan and skipped execution persist as alert")
                route(staff, "/ai-care/medication", "用药执行核对")
                choose_elder(staff, scoped=True)
                click_api(staff, staff.locator(".el-table__body tbody tr").filter(
                    has_text="E2E测试计划").get_by_role("button", name="已执行", exact=True), "/api/ai/medication/execute")
                route(staff, "/ai-care/daily", "每日护理助手")
                expect(staff.locator(".task-center:not(.notification-center)").first).not_to_contain_text("E2E测试计划")
                record("completed medication execution resolves its alert")

                route(staff, "/ai-care/health", "健康趋势观察")
                choose_elder(staff, scoped=True)
                staff.locator(".el-form-item").filter(has=staff.locator("label").filter(
                    has_text="体温")).get_by_role("spinbutton").fill("36.5")
                route(admin, "/ai-care/scope", "老人数据权限")
                row = admin.locator(".el-table__body tbody tr").filter(has_text="E2E护工")
                row.get_by_role("button", name="撤销", exact=True).click()
                click_api(admin, admin.locator(".el-message-box").get_by_role("button", name="确定", exact=True),
                          "/api/ai/scope/assignment", method="DELETE")
                revoked_contexts.add("staff")
                with staff.expect_response(lambda response: "/api/ai/health/measurement" in response.url
                                           and response.request.method == "POST") as forbidden:
                    staff.get_by_role("button", name="保存测量").click()
                assert forbidden.value.status == 403
                assert forbidden.value.json()["code"] == 403
                assert staff.evaluate('Boolean(JSON.parse(localStorage.getItem("stateData"))?.app?.token)')
                record("forbidden stale form returns 403 without logging out")
                route(staff, "/ai-care/health", "健康趋势观察")
                with staff.expect_response(lambda response: "/api/ai/scope/elders" in response.url) as response:
                    staff.get_by_role("button", name="选择", exact=True).first.click()
                assert response.value.json()["data"]["total"] == 0
                expect(staff.get_by_role("dialog", name="选择老人").locator(".el-table__body tbody tr")).to_have_count(0)
                record("elder permission revocation takes effect without relogin")
                # Revoke the actual Redis session while retaining browser state to exercise the shared 401 handler.
                expired_contexts.add("staff")
                logged_out = staff.evaluate('''async () => {
                    const token=JSON.parse(localStorage.getItem("stateData")).app.token;
                    const response=await fetch("/api/account/logout",{method:"DELETE",headers:{token}});
                    return (await response.json()).code;
                }''')
                assert logged_out == 200
                staff.goto(f"{origin}/?expired={time.monotonic_ns()}#/ai-care/daily")
                staff.wait_for_url(lambda url: "/login" in url)
                assert not staff.evaluate('JSON.parse(localStorage.getItem("stateData") || "null")?.app?.token')
                record("expired session redirects to login and clears browser credentials")

                def open_password():
                    admin.locator(".nav-right .el-dropdown").click()
                    admin.get_by_role("menuitem", name="修改密码", exact=True).click()
                    result = admin.get_by_role("dialog", name="修改密码", exact=True)
                    expect(result).to_be_visible()
                    return result

                password_requests = []
                admin.on("request", lambda request: password_requests.append(request)
                         if request.url.endswith("/api/account/edit") else None)
                change = open_password()
                for placeholder in ("旧密码", "新密码", "确认密码"):
                    expect(change.get_by_placeholder(placeholder, exact=True)).to_have_value("")
                next_password = "  e2e-password-new-20261020  "
                change.get_by_placeholder("旧密码", exact=True).fill(password)
                change.get_by_placeholder("新密码", exact=True).fill(next_password)
                change.get_by_placeholder("确认密码", exact=True).fill(next_password)
                old_submit = change.get_by_role("button", name="修改", exact=True).element_handle()
                assert old_submit is not None
                other_tab = admin.context.new_page()
                try:
                    login(other_tab, "18428167423")
                    # Exercise a click already queued against the previous account's dialog.
                    old_submit.dispatch_event("click")
                    admin.wait_for_timeout(100)
                    assert not password_requests
                    expect(change).not_to_be_visible()
                    login(other_tab, "13547584400")
                finally:
                    other_tab.close()
                admin.goto(f"{origin}/?password-reset={time.monotonic_ns()}{home_hash}")
                expect(admin.locator("#myEcharts canvas")).to_have_count(1)
                record("switching accounts in another tab prevents the old password dialog from sending")

                change = open_password()
                change.get_by_placeholder("旧密码", exact=True).fill("incorrect-test-old-password")
                change.get_by_placeholder("新密码", exact=True).fill(next_password)
                change.get_by_placeholder("确认密码", exact=True).fill(next_password)
                with admin.expect_response(lambda response: response.url.endswith("/api/account/edit")) as bad_password:
                    change.get_by_role("button", name="修改", exact=True).click()
                assert bad_password.value.json()["code"] == 400
                expect(change).to_be_visible()
                expect(change.get_by_placeholder("新密码", exact=True)).to_have_value(next_password)
                expect(change.get_by_role("button", name="修改", exact=True)).not_to_have_class(re.compile(r"\bis-loading\b"))
                change.get_by_placeholder("旧密码", exact=True).fill(password)
                def temporary_password_failure(request_route):
                    request_route.fulfill(status=200, content_type="application/json",
                                          body=json.dumps(dict(code=503, msg="E2E临时错误，请重试", data=None)))
                admin.route("**/api/account/edit", temporary_password_failure)
                change.get_by_role("button", name="修改", exact=True).click()
                expect(admin.get_by_text("E2E临时错误，请重试", exact=True).first).to_be_visible()
                expect(change.get_by_role("button", name="修改", exact=True)).not_to_have_class(re.compile(r"\bis-loading\b"))
                expect(change.get_by_placeholder("新密码", exact=True)).to_have_value(next_password)
                admin.unroute("**/api/account/edit", temporary_password_failure)
                record("password validation and a rejected request preserve form input and allow retry")

                held_passwords, logout_requests = [], []
                admin.on("request", lambda request: logout_requests.append(request)
                         if request.url.endswith("/api/account/logout") else None)
                def hold_password_response(request_route):
                    held_passwords.append((request_route, request_route.fetch()))
                admin.route("**/api/account/edit", hold_password_response)
                change.get_by_role("button", name="修改", exact=True).click()
                deadline = time.monotonic() + 10
                while not held_passwords and time.monotonic() < deadline:
                    admin.wait_for_timeout(20)
                assert len(held_passwords) == 1
                change.get_by_role("button", name="修改", exact=True).dispatch_event("click")
                admin.wait_for_timeout(80)
                assert len(held_passwords) == 1
                actual = held_passwords[0][0].request.post_data_json
                assert actual == dict(oldPass=password, newPass=next_password)
                held_passwords[0][0].fulfill(response=held_passwords[0][1])
                admin.unroute("**/api/account/edit", hold_password_response)
                admin.wait_for_url(lambda url: "/login" in url)
                assert not logout_requests, "successful password rotation already revoked the old token"
                assert not admin.evaluate('JSON.parse(localStorage.getItem("stateData") || "null")?.app?.token')
                for rejected_password in (password, next_password.strip()):
                    expect(admin.get_by_role("button", name="登录", exact=True)).not_to_have_class(re.compile(r"\bis-loading\b"))
                    admin.get_by_placeholder("账号", exact=True).fill("13547584400")
                    admin.get_by_placeholder("密码", exact=True).fill(rejected_password)
                    admin.get_by_placeholder("验证码", exact=True).fill(admin.evaluate('window.captchaDigits.slice(-4).join("")'))
                    with admin.expect_response(lambda response: response.url.endswith("/api/account/login")) as old_login:
                        admin.get_by_role("button", name="登录", exact=True).click()
                    assert old_login.value.status == 401
                login(admin, "13547584400", next_password)
                expect(admin.locator("#myEcharts canvas")).to_have_count(1)
                record("real password change sends the entered values once, exits locally and permits only the exact new password")
                assert not errors, errors
                admin.screenshot(path=str(evidence / "admin.png"))
                staff.screenshot(path=str(evidence / "staff.png"))
                record("no browser JavaScript or caught Vue console exceptions")
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
