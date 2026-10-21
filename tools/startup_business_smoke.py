"""Exercise the real Vue development proxy in an isolated startup acceptance copy.

No SQL fixtures, token bypass, static server or direct RAG indexing is used here.
Credentials stay in memory; evidence contains only synthetic operation identifiers.
"""
import argparse
import hashlib
import json
import re
import time
import uuid
from pathlib import Path

from playwright.sync_api import expect, sync_playwright

ROOT = Path(__file__).resolve().parents[1]


def run(project: Path, port: int, evidence: Path, phase: str):
    project = project.resolve()
    if not project.is_relative_to((ROOT / ".runtime/startup-check").resolve()):
        raise ValueError("Business writes require a copy inside this repository's .runtime/startup-check")
    credentials = project / ".runtime/demo-accounts.txt"
    lines = credentials.read_text(encoding="utf-8-sig").splitlines()
    account = next(line.split("：", 1)[1] for line in lines if line.startswith("账号："))
    password = next(line.split("：", 1)[1] for line in lines if line.startswith("密码："))
    credential_hash = hashlib.sha256(credentials.read_bytes()).hexdigest()
    evidence.mkdir(parents=True, exist_ok=True)
    state_path = evidence / "business-state.json"
    state = json.loads(state_path.read_text(encoding="utf-8")) if phase == "verify" else {
        "marker": "启动验收-" + uuid.uuid4().hex,
        "credentialsSha256": credential_hash,
    }
    assert credential_hash == state["credentialsSha256"], "Restart changed demo credentials"
    origin = f"http://127.0.0.1:{port}"
    errors, checks = [], []

    def record(name):
        checks.append(name)
        print("PASS: " + name, flush=True)

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context(viewport={"width": 1600, "height": 1050}, timezone_id="Asia/Shanghai")
        context.add_init_script(r"""window.captchaDigits=[];
            const draw=CanvasRenderingContext2D.prototype.fillText;
            CanvasRenderingContext2D.prototype.fillText=function(t,...a){
              if(this.canvas.width===120 && /^\d$/.test(t))window.captchaDigits.push(t);
              return draw.call(this,t,...a)};""")
        page = context.new_page()
        page.set_default_timeout(60000)
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.on("console", lambda message: errors.append(message.text) if message.type == "error" else None)
        try:
            page.goto(origin + "/#/login", timeout=180000)
            page.get_by_placeholder("账号", exact=True).fill(account)
            page.get_by_placeholder("密码", exact=True).fill(password)
            captcha = page.evaluate('window.captchaDigits.slice(-4).join("")')
            assert len(captcha) == 4, "Client CAPTCHA did not render"
            page.get_by_placeholder("验证码", exact=True).fill(captcha)
            with page.expect_response(lambda response: "/api/account/login" in response.url) as login:
                page.get_by_role("button", name="登录", exact=True).click()
            result = login.value.json()
            assert result.get("code") == 200, "Real demo login failed"
            token = result["data"]["token"]
            page.wait_for_url(lambda url: "/login" not in url)
            record("real browser login through the Vue development proxy")

            def api(path, payload=None):
                response = (context.request.get(origin + "/api" + path, headers={"token": token})
                            if payload is None else context.request.post(origin + "/api" + path,
                                                                        headers={"token": token}, data=payload))
                value = response.json()
                assert response.ok and value.get("code") == 200, (path, response.status, value.get("code"))
                return value.get("data")

            def route(path, heading):
                page.goto(origin + "/#" + path)
                expect(page.get_by_role("heading", name=heading, exact=True)).to_be_visible()

            if phase == "write":
                route("/ai-care/health", "健康趋势观察")
                with page.expect_response(lambda response: "/api/ai/scope/elders" in response.url) as elders:
                    page.get_by_role("button", name="选择", exact=True).first.click()
                available = elders.value.json()
                assert available.get("code") == 200 and available["data"]["list"], "Initialized demo has no accessible elders"
                state["elderId"] = available["data"]["list"][0]["id"]
                dialog = page.get_by_role("dialog", name="选择老人")
                dialog.locator(".el-table__body tbody tr").first.get_by_role("button", name="选择", exact=True).click()
                page.locator(".el-popconfirm__action .el-button--warning").click()
                expect(dialog).not_to_be_visible()
                page.locator(".el-form-item").filter(has=page.locator("label").filter(has_text="体温")).get_by_role("spinbutton").fill("36.7")
                page.locator(".el-form-item").filter(has=page.locator("label").filter(has_text="备注")).locator("textarea").fill(state["marker"])
                with page.expect_response(lambda response: "/api/ai/health/measurement" in response.url) as saved:
                    page.get_by_role("button", name="保存测量", exact=True).click()
                measurement = saved.value.json()
                assert measurement.get("code") == 200
                state["measurementId"] = measurement["data"]["id"]
                state_path.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
                record("elder selection and a real health measurement persisted through the UI")

                route("/ai-care/policy", "院内制度知识库")
                page.get_by_placeholder("例如：老人跌倒处理流程", exact=True).fill(state["marker"])
                page.get_by_placeholder("例如：护理部制度汇编 2026", exact=True).fill("隔离启动验收制度")
                page.get_by_placeholder("按段落粘贴制度正文，空行会分成检索片段", exact=True).fill(
                    "老人跌倒后，工作人员应立即通知值班护士，观察意识与呼吸，保护现场并完成事故记录。"
                    "未经评估不要随意搬动老人，按院内流程联系医生处理。")
                with page.expect_response(lambda response: "/api/ai/policy/import" in response.url) as imported:
                    page.get_by_role("button", name="导入知识库", exact=True).click()
                assert imported.value.json().get("code") == 200
                record("policy import through the real browser and Java service")

            trend = api(f'/ai/health/trend?elderId={state["elderId"]}&limit=90')
            matching = [item for item in trend["records"] if item["id"] == state["measurementId"]]
            assert len(matching) == 1 and matching[0]["remarks"] == state["marker"]
            assert matching[0]["temperature"] == 36.7
            record("health measurement confirmed by a separate authenticated read" + (" after restart" if phase == "verify" else ""))

            deadline = time.monotonic() + 150
            while True:
                matching = [doc for doc in api("/ai/policy/documents") if doc["title"] == state["marker"]]
                if len(matching) == 1 and matching[0]["ragStatus"] == "SYNCED":
                    break
                if time.monotonic() >= deadline:
                    raise AssertionError("Java outbox did not synchronize the document to the real Python/Milvus service")
                page.wait_for_timeout(1000)
            record("asynchronous Java outbox reached persisted SYNCED state")
            route("/ai-care/policy", "院内制度知识库")
            expect(page.locator(".catalog-item").filter(has_text=state["marker"])).to_contain_text("已同步")
            page.get_by_placeholder("例如：老人跌倒后应该先做什么？", exact=True).fill("老人跌倒后应该先做什么？")
            with page.expect_response(lambda response: "/api/ai/policy/query" in response.url) as queried:
                page.get_by_role("button", name="检索制度", exact=True).click()
            answer = queried.value.json()
            assert answer.get("code") == 200
            answer = answer["data"]
            assert answer["grounded"] and answer["retrievalBackend"] == "milvus-hybrid", "A fallback is not a successful Milvus acceptance"
            assert any(citation["title"] == state["marker"] and "值班护士" in citation["content"] for citation in answer["citations"])
            assert answer["modelUsed"] is False, "Acceptance must not call a paid model"
            expect(page.locator(".citations")).to_contain_text(state["marker"])
            record("real BGE/Milvus retrieval returned the imported policy with a visible source citation" + (" after restart" if phase == "verify" else ""))
            assert not errors, "Unexpected browser errors: " + "; ".join(errors)
            page.screenshot(path=str(evidence / f"business-{phase}.png"), full_page=True)
            (evidence / f"business-{phase}.json").write_text(json.dumps({"status": "PASS", "checks": checks}, ensure_ascii=False, indent=2), encoding="utf-8")
        except BaseException:
            # Screenshots omit the login page to avoid writing even masked credential state.
            if "/login" not in page.url:
                page.screenshot(path=str(evidence / f"business-{phase}-failure.png"), full_page=True)
            raise
        finally:
            context.close()
            browser.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", type=Path, required=True)
    parser.add_argument("--frontend-port", type=int, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--phase", choices=("write", "verify"), required=True)
    args = parser.parse_args()
    run(args.project_root, args.frontend_port, args.evidence, args.phase)
