import time

import httpx

from conftest import KEY, make_settings
from fakes import PNG, ServerThread
from gateway.app import create_app


def wait_job(client, job_id, timeout=15):
    deadline = time.time() + timeout
    while time.time() < deadline:
        j = client.get(f"/v1/images/jobs/{job_id}", params={"wait": 2}).json()
        if j["status"] in ("succeeded", "failed", "cancelled"):
            return j
    raise AssertionError("job did not finish")


def test_workflows_listed(client):
    data = client.get("/v1/workflows").json()["data"]
    by_id = {w["id"]: w for w in data}
    assert set(by_id) == {"flux-dev", "sdxl-basic"}
    assert "prompt" in by_id["sdxl-basic"]["params"] and by_id["sdxl-basic"]["defaults"]["width"] == 1024


def test_checkpoints(client):
    assert "sd_xl_base_1.0.safetensors" in client.get("/v1/comfy/checkpoints").json()["data"]


def test_job_lifecycle(client, comfy, gateway):
    r = client.post("/v1/images/jobs", json={"workflow": "sdxl-basic", "prompt": "a red bike", "size": "832x1216",
                                             "steps": 12, "seed": 99})
    assert r.status_code == 202, r.text
    job = r.json()
    assert job["status"] in ("queued", "running")
    done = wait_job(client, job["id"])
    assert done["status"] == "succeeded", done
    assert len(done["images"]) == 1 and done["images"][0]["seed"] == 99

    # graph injected correctly
    graphs = comfy.server.config.app.state.prompts
    g = list(graphs.values())[-1]
    assert g["6"]["inputs"]["text"] == "a red bike"
    assert (g["5"]["inputs"]["width"], g["5"]["inputs"]["height"]) == (832, 1216)
    assert g["3"]["inputs"]["steps"] == 12 and g["3"]["inputs"]["seed"] == 99

    # signed URL works without the API key; tampered one doesn't
    url = done["images"][0]["url"]
    assert url.startswith(gateway.url)
    img = httpx.get(url)
    assert img.status_code == 200 and img.content == PNG
    assert httpx.get(url.replace("sig=", "sig=0")).status_code == 401
    # bare path with API key works too
    bare = done["images"][0]["path"].split("?")[0]
    assert client.get(bare).status_code == 200
    assert httpx.get(gateway.url + bare).status_code == 401

    # history listing
    lst = client.get("/v1/images/jobs").json()
    assert lst["data"][0]["id"] == job["id"]

    # delete
    assert client.delete(f"/v1/images/jobs/{job['id']}").json()["deleted"] is True
    assert client.get(f"/v1/images/jobs/{job['id']}").status_code == 404
    assert httpx.get(url).status_code == 404


def test_batch_n(client):
    j = client.post("/v1/images/jobs", json={"prompt": "dogs", "workflow": "sdxl-basic", "n": 3}).json()
    done = wait_job(client, j["id"])
    assert done["status"] == "succeeded" and len(done["images"]) == 3


def test_n_without_batch_support_runs_sequentially(client, workflows_dir):
    import json
    g = {"1": {"class_type": "KSampler", "inputs": {"seed": 1, "positive": ["2", 0]}},
         "2": {"class_type": "CLIPTextEncode", "inputs": {"text": "x"}},
         "9": {"class_type": "SaveImage", "inputs": {"images": ["1", 0]}}}
    (workflows_dir / "nobatch.json").write_text(json.dumps(g))
    j = client.post("/v1/images/jobs", json={"prompt": "cats", "workflow": "nobatch", "n": 2, "seed": 10}).json()
    done = wait_job(client, j["id"])
    assert done["status"] == "succeeded"
    assert [i["seed"] for i in done["images"]] == [10, 11]


def test_validation_errors(client):
    assert client.post("/v1/images/jobs", json={"prompt": "", "workflow": "sdxl-basic"}).status_code == 422
    r = client.post("/v1/images/jobs", json={"prompt": "x", "workflow": "nope"})
    assert r.status_code == 400 and "Unknown workflow" in r.json()["error"]["message"]
    assert client.post("/v1/images/jobs", json={"prompt": "x", "size": "big"}).status_code == 400
    assert client.post("/v1/images/jobs", json={"prompt": "x", "n": 50}).status_code == 422


def test_comfy_execution_error(client):
    j = client.post("/v1/images/jobs", json={"prompt": "please FAIL", "workflow": "sdxl-basic"}).json()
    done = wait_job(client, j["id"])
    assert done["status"] == "failed" and "out of memory" in done["error"]


def test_comfy_validation_error_surfaces(client):
    j = client.post("/v1/images/jobs", json={"prompt": "x", "workflow": "sdxl-basic", "checkpoint": "missing.safetensors"}).json()
    done = wait_job(client, j["id"])
    assert done["status"] == "failed" and "not in" in done["error"]


def test_cancel_running_and_queued(client):
    a = client.post("/v1/images/jobs", json={"prompt": "slow a", "workflow": "sdxl-basic"}).json()
    b = client.post("/v1/images/jobs", json={"prompt": "slow b", "workflow": "sdxl-basic"}).json()
    # b is queued behind a (1 worker)
    assert client.get(f"/v1/images/jobs/{b['id']}").json()["status"] == "queued"
    assert client.post(f"/v1/images/jobs/{b['id']}/cancel").json()["status"] == "cancelled"
    # wait until a is running in comfy, then cancel
    deadline = time.time() + 5
    while client.get(f"/v1/images/jobs/{a['id']}").json()["status"] != "running" and time.time() < deadline:
        time.sleep(0.02)
    client.post(f"/v1/images/jobs/{a['id']}/cancel")
    assert wait_job(client, a["id"])["status"] == "cancelled"
    assert wait_job(client, b["id"])["status"] == "cancelled"


def test_openai_images_generations(client):
    r = client.post("/v1/images/generations", json={"model": "flux-dev", "prompt": "a lighthouse", "size": "512x512"})
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["data"][0]["url"].startswith("http") and d["job_id"].startswith("img_")
    r = client.post("/v1/images/generations", json={"prompt": "b64 please", "response_format": "b64_json"})
    import base64
    assert base64.b64decode(r.json()["data"][0]["b64_json"]) == PNG


def test_jobs_survive_restart(tmp_path, lmstudio, comfy, workflows_dir):
    s = make_settings(tmp_path, lmstudio.url, comfy.url, workflows_dir)
    h = {"Authorization": f"Bearer {KEY}"}
    with ServerThread(create_app(s)) as gw:
        j = httpx.post(gw.url + "/v1/images/jobs", headers=h, json={"prompt": "persist", "workflow": "sdxl-basic"}).json()
    # gateway stopped, likely before finishing; start a new one on the same data dir
    s2 = make_settings(tmp_path, lmstudio.url, comfy.url, workflows_dir)
    with ServerThread(create_app(s2)) as gw2:
        with httpx.Client(base_url=gw2.url, headers=h, timeout=30) as c:
            done = wait_job(c, j["id"])
            assert done["status"] == "succeeded", done
            assert len(done["images"]) == 1
