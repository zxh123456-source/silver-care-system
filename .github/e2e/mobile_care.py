"""Mobile UI workflows against the same real backend and disposable database.

Run after the existing desktop checks: the isolated elder has no initial records,
so new measurements, plans and alerts cannot change the desktop fixture counts.
Only the first save response is replaced with a precise, retryable HTTP 503.
"""
import json
import re
import urllib.parse
from pathlib import Path

from playwright.sync_api import expect


MOBILE_ELDER_ID = 867103
MOBILE_ELDER = "E2E移动护理长姓名"
PAGES = (
    ("care", "/ai-care/workbench", "护理记录与交班"),
    ("health", "/ai-care/health", "健康趋势观察"),
    ("medication", "/ai-care/medication", "用药执行核对"),
    ("daily", "/ai-care/daily", "每日护理助手"),
)


def run_mobile_care(*, new_page, login_admin, route, click_api, select_option,
                    allow_console_once, record, evidence: Path):
    """Keep shared authentication/error collection/JUnit behavior in the runner."""
    label = "mobile-care"
    page = new_page(label)
    page.set_viewport_size({"width": 390, "height": 844})
    login_admin(page)
    metrics = []
    business_evidence = {}

    def proof(name, value):
        business_evidence[name] = value
        (evidence / "mobile-business-evidence.json").write_text(
            json.dumps(business_evidence, ensure_ascii=False, indent=2), encoding="utf-8")

    def snapshot(name, full_page=False):
        expect(page.locator(".route-view")).not_to_have_class(re.compile(r".*fade-transform-(?:enter|leave)-active.*"))
        expect(page.locator(".dialog-fade-enter-active, .el-zoom-in-top-enter-active")).to_have_count(0)
        # Preserve inline errors while letting transient success/error toasts
        # expire naturally so final visual evidence shows the stable UI.
        expect(page.locator(".el-message:visible")).to_have_count(0, timeout=10000)
        page.screenshot(path=str(evidence / f"mobile-{name}.png"), full_page=full_page)

    def field(label_text, role="textbox", scope=None):
        scope = scope or page
        return scope.locator(".el-form-item").filter(
            has=page.locator("label").filter(has_text=label_text)).get_by_role(role)

    def api(path, method="GET", data=None):
        # Real authenticated API reads verify persistence; the one POST fixture
        # creates an independent old source that fits the existing 200-row limit.
        result = page.evaluate("""async ({path, method, data}) => {
            const token = JSON.parse(localStorage.getItem('stateData')).app.token;
            const response = await fetch(path, {method, headers: {token,
                'Content-Type': 'application/json'},
                body: data === null ? undefined : JSON.stringify(data)});
            return {status: response.status, body: await response.json()};
        }""", {"path": path, "method": method, "data": data})
        assert result["status"] == 200 and result["body"]["code"] == 200, (path, result)
        return result["body"]["data"]

    def within_viewport(locator, name, touch=False, scroll=True, vertical=False):
        expect(locator).to_be_visible()
        if scroll:
            locator.scroll_into_view_if_needed()
        box = locator.bounding_box()
        width = page.viewport_size["width"]
        assert box and box["x"] >= -1 and box["x"] + box["width"] <= width + 1, (name, width, box)
        if vertical:
            assert box["y"] >= -1 and box["y"] + box["height"] <= page.viewport_size["height"] + 1, (name, box)
        if touch:
            assert box["height"] >= 43.5 and box["width"] >= 43.5, (name, width, box)
        return {"name": name, "width": round(box["width"], 2), "height": round(box["height"], 2)}

    def layout(name, buttons=()):
        # Existing route transitions translate the page for 280ms. Measure the
        # settled UI rather than a valid intermediate animation frame.
        expect(page.locator(".route-view")).not_to_have_class(re.compile(r".*fade-transform-(?:enter|leave)-active.*"))
        measured = page.evaluate("""() => ({
            width: innerWidth, document: document.documentElement.scrollWidth,
            main: [...document.querySelectorAll('.el-main, .route-view')].map(e => ({
                client: e.clientWidth, scroll: e.scrollWidth,
                left: e.getBoundingClientRect().left, right: e.getBoundingClientRect().right
            }))
        })""")
        assert measured["document"] <= measured["width"], (name, measured)
        for main in measured["main"]:
            assert main["scroll"] <= main["client"] + 1, (name, measured)
            assert main["left"] >= -1 and main["right"] <= measured["width"] + 1, (name, measured)
        measured.update(name=name, buttons=[within_viewport(button, title, touch=True)
                                            for title, button in buttons])
        metrics.append(measured)
        (evidence / "mobile-metrics.json").write_text(
            json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")

    def choose_mobile_elder():
        with page.expect_response(lambda response: "/api/ai/scope/elders" in response.url) as response:
            page.get_by_role("button", name="选择", exact=True).first.click()
        result = response.value.json()
        assert result["code"] == 200
        assert any(row["id"] == MOBILE_ELDER_ID and row["name"] == MOBILE_ELDER
                   for row in result["data"]["list"]), "Disposable mobile elder fixture is missing"
        dialog = page.get_by_role("dialog", name="选择老人")
        within_viewport(dialog, "elder picker")
        row = dialog.locator(".el-table__body tbody tr").filter(has_text=MOBILE_ELDER)
        expect(row).to_have_count(1)
        choose = row.get_by_role("button", name="选择", exact=True)
        # Playwright can automatically scroll a wide table to an offscreen button;
        # require the primary choice to be reachable without that hidden help.
        within_viewport(choose, "visible elder selection", touch=True, scroll=False)
        choose.click()
        confirm = page.locator(".el-popconfirm__action .el-button--warning")
        within_viewport(confirm, "confirm selected elder", touch=True, scroll=False, vertical=True)
        snapshot(f"elder-confirm-{page.viewport_size['width']}")
        confirm.click()
        expect(dialog).not_to_be_visible()
        expect(page.get_by_placeholder("请选择老人", exact=True)).to_have_value(MOBILE_ELDER)

    def fail_first_save(endpoint, button, message, feedback):
        requests = []
        allow_console_once(label, re.escape(endpoint),
                           r"Failed to load resource: the server responded with a status of 503 \(.*\)")

        def unavailable(request_route):
            assert request_route.request.method == "POST"
            requests.append(request_route.request.post_data_json)
            request_route.fulfill(status=503, content_type="application/json",
                                  body=json.dumps({"code": 503, "msg": message}, ensure_ascii=False))

        pattern = "**" + endpoint
        page.route(pattern, unavailable, times=1)
        try:
            with page.expect_response(lambda response: urllib.parse.urlparse(response.url).path == endpoint
                                      and response.request.method == "POST") as response:
                button.click()
            assert response.value.status == 503 and response.value.json()["code"] == 503
            expect(button).not_to_have_class(re.compile(r"\bis-loading\b"))
            expect(button).to_be_enabled()
            # The message must remain in the page's inline feedback after the
            # transient global toast disappears; Vue exceptions are never allowed.
            expect(page.locator(".route-view .el-alert").filter(has_text=feedback)).to_be_visible()
            assert len(requests) == 1
            return requests[0]
        finally:
            page.unroute(pattern, unavailable)

    # Capture every initial page even on the old bundle before the first mobile
    # assertion. The pre-change run therefore leaves useful visual evidence.
    for slug, path, title in PAGES:
        route(page, path, title)
        snapshot(f"{slug}-390-initial")
    route(page, PAGES[0][1], PAGES[0][2])
    for width in (320, 430):
        page.set_viewport_size({"width": width, "height": 844})
        with page.expect_response(lambda response: "/api/ai/scope/elders" in response.url):
            page.get_by_role("button", name="选择", exact=True).first.click()
        picker = page.get_by_role("dialog", name="选择老人")
        expect(picker).to_be_visible()
        expect(picker.locator(".el-table__body tbody tr").filter(has_text=MOBILE_ELDER)).to_have_count(1)
        expect(page.locator(".dialog-fade-enter-active")).to_have_count(0)
        expect(picker.locator(".el-loading-mask:visible")).to_have_count(0)
        snapshot(f"elder-picker-{width}-initial")
        pagination = picker.locator(".el-pagination .btn-prev, .el-pagination .btn-next, .el-pagination .el-pager li")
        assert pagination.count() >= 3, "Fixture picker must expose previous, current and next page controls"
        for index, control in enumerate(pagination.all()):
            within_viewport(control, f"elder pagination {width}/{index}", touch=True, scroll=False)
        page.keyboard.press("Escape")
        expect(picker).not_to_be_visible()

    for width in (320, 390, 430):
        page.set_viewport_size({"width": width, "height": 844})
        for slug, path, title in PAGES:
            opener = page.get_by_role("button", name="打开导航", exact=True)
            within_viewport(opener, "navigation opener", touch=True)
            opener.click()
            drawer = page.get_by_role("dialog", name="页面导航", exact=True)
            within_viewport(drawer, "navigation drawer")
            menu = drawer.get_by_role("menuitem", name=title, exact=True)
            if not menu.is_visible():
                drawer.locator(".el-sub-menu__title").filter(has_text="AI护理工作台").click()
            expect(menu).to_have_count(1)
            if slug == "care":
                snapshot(f"navigation-{width}")
            menu.click()
            expect(page.get_by_role("heading", name=title, exact=True)).to_be_visible()
            expect(drawer).not_to_be_visible()
            expect(opener).to_have_attribute("aria-expanded", "false")
            assert page.evaluate("location.hash") == "#" + path
            layout(f"{slug}-{width}-navigation")
    record("mobile navigation opens and closes after selecting each care route at 320px, 390px and 430px")

    page.set_viewport_size({"width": 320, "height": 844})
    route(page, PAGES[0][1], PAGES[0][2])
    choose_mobile_elder()
    page.set_viewport_size({"width": 390, "height": 844})
    original = ("E2E移动长文本：老人今天下午没有参加集体活动，表示昨晚休息不充分，"
                "工作人员已提供安静休息环境并记录本人反馈，明天早班需要继续询问休息情况。" * 3)
    observation = "人工核对后的观察：下午自主选择休息，未作医学判断。"
    action = "人工确认已提供安静环境，并向下一班说明本次观察范围。"
    follow_up = "E2E移动护理跟进：明天早班继续询问昨夜休息情况并按原始反馈登记。"
    field("护理员口述").fill(original)
    draft = click_api(page, page.get_by_role("button", name="生成结构化草稿", exact=True), "/api/ai/care/draft")["data"]
    assert draft["elderId"] == MOBILE_ELDER_ID and draft["sourceText"] == original
    assert draft["modelUsed"] is False, "This E2E environment intentionally disables the model provider"
    field("观察到的情况").fill(observation)
    field("已采取措施").fill(action)
    field("待跟进事项").fill(follow_up)
    field("进餐").fill("午餐按本人反馈登记")
    save = page.get_by_role("button", name="确认并保存", exact=True)
    failed_payload = fail_first_save("/api/ai/care/save", save, "E2E护理保存暂不可用，请保留草稿后重试", "草稿已保留")
    for title, value in (("护理员口述", original), ("观察到的情况", observation),
                         ("已采取措施", action), ("待跟进事项", follow_up)):
        expect(field(title)).to_have_value(value)
    assert not [note for note in api("/api/ai/care/handover")["notes"]
                if note["elderId"] == MOBILE_ELDER_ID], "Rejected save must not create a note"
    layout("care-390-preserved-draft", [("save care", save)])
    snapshot("care-390-preserved-draft")
    with page.expect_request(lambda request: urllib.parse.urlparse(request.url).path == "/api/ai/care/save"
                             and request.method == "POST") as retry:
        saved = click_api(page, save, "/api/ai/care/save")["data"]
    assert retry.value.post_data_json == failed_payload
    assert saved["elderId"] == MOBILE_ELDER_ID and saved["observation"] == observation
    note_id = saved["id"]
    handover = click_api(page, page.get_by_role("button", name="生成交班摘要", exact=True),
                         "/api/ai/care/handover", "GET")["data"]
    persisted = [note for note in handover["notes"] if note["id"] == note_id]
    assert len(persisted) == 1 and persisted[0]["sourceText"] == original
    assert persisted[0]["followUp"] == follow_up and persisted[0]["actionTaken"] == action
    assert handover["modelUsed"] is False
    report = click_api(page, page.get_by_role("button", name="生成周报草稿", exact=True),
                       "/api/ai/care/family-report")["data"]
    assert report["sourceCount"] == 1 and report["modelUsed"] is False
    expect(page.locator(".family-report textarea")).to_have_value(report["summary"])
    expect(page.locator(".family-report")).to_contain_text("人工")
    proof("care", {"elderId": MOBILE_ELDER_ID, "noteId": note_id,
                   "rejectedSaveStatus": 503, "retryPayloadUnchanged": True,
                   "handoverMatchingNotes": len(persisted), "sourceCount": report["sourceCount"],
                   "draftModelUsed": draft["modelUsed"], "reportModelUsed": report["modelUsed"]})
    record("mobile care draft retains human edits after one 503, saves once on retry and returns real handover notes and report sources")

    for width in (320, 390, 430):
        page.set_viewport_size({"width": width, "height": 844})
        page.locator(".note-preview").filter(has_text=follow_up).click()
        detail = page.get_by_role("dialog", name="护理记录详情", exact=True)
        within_viewport(detail, "care detail")
        expect(detail).to_contain_text(original)
        expect(detail.get_by_role("textbox")).to_have_count(0)
        within_viewport(detail.get_by_role("button", name="编辑记录", exact=True), "edit care", touch=True)
        snapshot(f"care-detail-{width}")
        detail.get_by_role("button", name="编辑记录", exact=True).click()
        expect(field("原始口述", scope=detail)).to_be_editable()
        expect(field("原始口述", scope=detail)).to_have_value(original)
        within_viewport(detail.get_by_role("button", name="保存修改", exact=True), "save edited care", touch=True)
        detail.get_by_role("button", name="关闭", exact=True).click()
        expect(detail).not_to_be_visible()
        layout(f"care-{width}-long-text", [("generate report", page.get_by_role("button", name="生成周报草稿", exact=True))])
    record("mobile care details remain read-only until editing and fit 320px, 390px and 430px with touch-sized primary actions")

    page.set_viewport_size({"width": 390, "height": 844})
    route(page, PAGES[1][1], PAGES[1][2])
    choose_mobile_elder()
    remarks = "E2E移动测量备注：本次数据来自工作人员现场录入，需结合记录时间人工核对，不代表医疗诊断。" * 3
    temperature = field("体温", "spinbutton")
    temperature.fill("36.6")
    field("心率", "spinbutton").fill("76")
    field("备注").fill(remarks)
    save_health = page.get_by_role("button", name="保存测量", exact=True)
    failed_health = fail_first_save("/api/ai/health/measurement", save_health,
                                   "E2E测量保存暂不可用，请保留输入后重试", "输入已保留")
    expect(temperature).to_have_value("36.6")
    expect(field("备注")).to_have_value(remarks)
    assert not api(f"/api/ai/health/trend?elderId={MOBILE_ELDER_ID}")["records"]
    for width in (320, 390, 430):
        page.set_viewport_size({"width": width, "height": 844})
        layout(f"health-{width}-preserved-long-input", [("retry measurement", save_health)])
    page.set_viewport_size({"width": 390, "height": 844})
    snapshot("health-390-preserved-input")
    with page.expect_request(lambda request: urllib.parse.urlparse(request.url).path == "/api/ai/health/measurement"
                             and request.method == "POST") as retried_health:
        click_api(page, save_health, "/api/ai/health/measurement")
    assert retried_health.value.post_data_json == failed_health
    trend = api(f"/api/ai/health/trend?elderId={MOBILE_ELDER_ID}")
    assert len(trend["records"]) == 1
    assert trend["records"][0]["temperature"] == 36.6 and trend["records"][0]["remarks"] == remarks
    proof("health", {"rejectedSaveStatus": 503, "retryPayloadUnchanged": True,
                     "persistedMeasurementCount": len(trend["records"])})
    expect(page.locator(".chart canvas").first).to_be_visible()
    for width in (320, 390, 430):
        page.set_viewport_size({"width": width, "height": 844})
        layout(f"health-{width}-saved", [("save measurement", save_health)])
    snapshot("health-430-saved")
    record("mobile health measurement preserves all input on one 503 and retries to exactly one persisted measurement and chart")

    page.set_viewport_size({"width": 390, "height": 844})
    route(page, PAGES[2][1], PAGES[2][2])
    choose_mobile_elder()
    medicine = "E2E移动医嘱执行计划长中文名称核对样例"
    dose = "严格按已登记医嘱由工作人员核对，本测试不包含真实药物或剂量建议。" * 3
    field("药品名称").fill(medicine)
    field("执行说明").fill(dose)
    selector = page.locator(".el-form-item").filter(has=page.locator("label").filter(has_text="执行时段")).locator(".el-select")
    for period in ("早", "晚"):
        select_option(page, selector, period)
        page.get_by_role("heading", name="用药执行核对", exact=True).click()
    click_api(page, page.get_by_role("button", name="保存计划", exact=True), "/api/ai/medication/plan")
    cards = page.locator(".mobile-records .mobile-record").filter(has_text=medicine)
    expect(cards).to_have_count(2)
    morning = cards.filter(has=page.get_by_text("早", exact=True))
    evening = cards.filter(has=page.get_by_text("晚", exact=True))
    click_api(page, morning.get_by_role("button", name="已执行", exact=True), "/api/ai/medication/execute")
    evening.get_by_role("button", name="未执行", exact=True).click()
    skipped = page.get_by_role("dialog", name="登记未执行", exact=True)
    for width in (320, 430):
        page.set_viewport_size({"width": width, "height": 844})
        within_viewport(skipped, "skipped medication prompt")
        within_viewport(skipped.get_by_role("button", name="确定", exact=True), "confirm skipped reason", touch=True)
        snapshot(f"medication-reason-{width}")
    skipped.get_by_role("button", name="确定", exact=True).click()
    expect(skipped).to_contain_text("原因不能为空")
    reason = "E2E移动未执行原因：老人暂未接受本次安排，已记录情况并通知值班人员继续人工核对。" * 2
    skipped.get_by_role("textbox").fill(reason)
    click_api(page, skipped.get_by_role("button", name="确定", exact=True), "/api/ai/medication/execute")
    expect(skipped).not_to_be_visible()
    current_day = page.evaluate("new Date().toLocaleDateString('en-CA')")
    day = api(f"/api/ai/medication/day?elderId={MOBILE_ELDER_ID}&date={current_day}")
    assert day["total"] == 2 and day["done"] == 1 and day["skipped"] == 1
    assert next(task for task in day["tasks"] if task["period"] == "晚")["note"] == reason
    proof("medication", {key: day[key] for key in ("total", "done", "skipped", "pending")})
    route(page, PAGES[2][1], PAGES[2][2])
    choose_mobile_elder()
    expect(evening).to_contain_text(reason)
    for width in (320, 390, 430):
        page.set_viewport_size({"width": width, "height": 844})
        layout(f"medication-{width}-long-plan", [("save plan", page.get_by_role("button", name="保存计划", exact=True)),
                                                ("mark execution", morning.get_by_role("button", name="已执行", exact=True))])
    page.set_viewport_size({"width": 390, "height": 844})
    evening.scroll_into_view_if_needed()
    snapshot("medication-390-saved-plan")
    record("mobile medication plan persists both execution and a required skipped reason with responsive cards and dialogs")

    old_date = page.evaluate("""() => {const d = new Date(); d.setDate(d.getDate() - 4);
        return d.toLocaleDateString('en-CA');}""")
    daily_follow_up = "E2E移动独立协作事项：下一班核对生活照护反馈，协作复核完成后原护理待办仍须单独确认。"
    daily_note = api("/api/ai/care/save", "POST", {"elderId": MOBILE_ELDER_ID,
        "sourceText": "E2E移动独立历史来源，工作人员已登记需下一班继续询问。",
        "observation": "仅记录本次反馈", "followUp": daily_follow_up,
        "eventTime": old_date + " 09:00:00"})
    route(page, PAGES[3][1], PAGES[3][2])
    click_api(page, page.get_by_role("button", name="全量同步所选日期事项", exact=True), "/api/ai/daily/tasks/sync")
    tasks = page.locator(".task-center:not(.notification-center)").nth(1)
    task = tasks.locator(".mobile-records .mobile-record").filter(has_text=daily_follow_up)
    expect(task).to_have_count(1)
    click_api(page, task.get_by_role("button", name="认领", exact=True), "/api/ai/daily/tasks/claim")
    expect(task).to_contain_text("已认领")
    task.get_by_role("button", name="复核完成", exact=True).click()
    completed = page.get_by_role("dialog", name="复核完成", exact=True)
    within_viewport(completed, "daily completion prompt")
    completion_note = "E2E移动已复核协作来源，保留原护理事项为待跟进状态，等待原页面工作人员进一步确认。"
    completed.get_by_role("textbox").fill(completion_note)
    within_viewport(completed.get_by_role("button", name="确定", exact=True), "complete daily task", touch=True)
    snapshot("daily-complete-390")
    click_api(page, completed.get_by_role("button", name="确定", exact=True), "/api/ai/daily/tasks/complete")
    select_option(page, tasks.locator(".el-select"), "已完成")
    expect(task).to_contain_text(completion_note)
    click_api(page, page.get_by_role("button", name="全量同步所选日期事项", exact=True), "/api/ai/daily/tasks/sync")
    expect(task).to_contain_text(completion_note)
    source = next(note for note in api("/api/ai/care/handover?date=" + old_date)["notes"] if note["id"] == daily_note["id"])
    for key in ("sourceText", "observation", "followUp", "status"):
        assert source[key] == daily_note[key], (key, source, daily_note)
    assert source["status"] == "PENDING"
    proof("daily", {"sourceNoteId": source["id"], "sourceStatusAfterCompletion": source["status"],
                    "sourceFieldsUnchanged": True, "completionSurvivesResync": True})
    medication_after_task = api(f"/api/ai/medication/day?elderId={MOBILE_ELDER_ID}&date={current_day}")
    assert medication_after_task["done"] == 1 and medication_after_task["skipped"] == 1
    for width in (320, 390, 430):
        page.set_viewport_size({"width": width, "height": 844})
        layout(f"daily-{width}-long-records", [("sync tasks", page.get_by_role("button", name="全量同步所选日期事项", exact=True))])
    page.set_viewport_size({"width": 390, "height": 844})
    task.scroll_into_view_if_needed()
    snapshot("daily-390-completed-task")
    record("mobile daily claim and completion survive resync without changing the original pending care note or medication records")

    date_metrics = []
    for slug, path, title in PAGES:
        route(page, path, title)
        viewports = [(320, 844), (390, 844), (430, 844)]
        if slug == "health":
            viewports.append((390, 500))
        for width, height in viewports:
            page.set_viewport_size({"width": width, "height": height})
            date_input = page.locator(".el-date-editor input").first
            date_input.click()
            calendar = page.locator(".el-picker-panel:visible").first
            expect(calendar).to_be_visible()
            expect(page.locator(".el-zoom-in-top-enter-active")).to_have_count(0)
            suffix = str(width) if height == 844 else f"{width}x{height}"
            snapshot(f"{slug}-date-{suffix}")
            date_metrics.append(dict(page=slug, viewport=width, viewportHeight=height, **calendar.bounding_box()))
            (evidence / "calendar-metrics.json").write_text(
                json.dumps(date_metrics, ensure_ascii=False, indent=2), encoding="utf-8")
            within_viewport(calendar, f"{slug} date picker", scroll=False, vertical=True)
            if slug == "health":
                confirm_date = calendar.get_by_role("button", name="确定", exact=True)
                within_viewport(confirm_date,
                                "confirm measurement time", touch=True, scroll=False, vertical=True)
                confirm_date.click()
            else:
                page.keyboard.press("Escape")
            expect(calendar).not_to_be_visible()
    record("mobile date and datetime popovers fit all four care pages at 320px, 390px and 430px")
