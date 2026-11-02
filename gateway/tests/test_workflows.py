import json
import random

import pytest

from gateway.workflows import WorkflowError, WorkflowStore, load_workflow


def test_sdxl_autodetect(workflows_dir):
    wf = load_workflow(workflows_dir / "sdxl-basic.json")
    assert {t.node for t in wf.targets["prompt"]} == {"6"}
    assert {t.node for t in wf.targets["negative_prompt"]} == {"7"}
    for p in ("seed", "steps", "cfg", "width", "height", "batch_size", "checkpoint"):
        assert p in wf.targets, p
    assert "prompt" not in wf.defaults  # never leak the example prompt
    assert wf.defaults["width"] == 1024 and wf.defaults["steps"] == 25
    assert wf.title == "SDXL (basic)"

    graph, seed = wf.build({"prompt": "a cat", "width": 1001, "steps": 999, "seed": 5}, random.Random(1))
    assert graph["6"]["inputs"]["text"] == "a cat"
    assert graph["7"]["inputs"]["text"] == "blurry, low quality, watermark, text"  # meta default
    assert graph["5"]["inputs"]["width"] == 1000  # rounded to multiple of 8
    assert graph["3"]["inputs"]["steps"] == 200  # clamped
    assert graph["3"]["inputs"]["seed"] == 5 and seed == 5
    assert graph["6"]["inputs"]["clip"] == ["4", 1]  # links untouched
    # original graph untouched
    assert wf.graph["6"]["inputs"]["text"].startswith("a cozy cabin")


def test_random_seed_when_missing(workflows_dir):
    wf = load_workflow(workflows_dir / "sdxl-basic.json")
    g1, s1 = wf.build({"prompt": "x"}, random.Random(1))
    g2, s2 = wf.build({"prompt": "x", "seed": -1}, random.Random(2))
    assert s1 != s2 and g1["3"]["inputs"]["seed"] == s1


def test_flux_trace_through_guider(workflows_dir):
    wf = load_workflow(workflows_dir / "flux-dev.json")
    assert [t.node for t in wf.targets["prompt"]] == ["6"]
    assert "negative_prompt" not in wf.targets
    assert [t.input for t in wf.targets["seed"]] == ["noise_seed"]
    assert [t.node for t in wf.targets["steps"]] == ["17"]
    assert {t.node for t in wf.targets["width"]} == {"27"}
    graph, _ = wf.build({"prompt": "fox", "negative_prompt": "ignored", "height": 768})
    assert graph["6"]["inputs"]["text"] == "fox"
    assert graph["27"]["inputs"]["height"] == 768


def test_placeholders(tmp_path):
    g = {
        "1": {"class_type": "MyCustomSampler", "inputs": {"seed": "{{seed}}", "steps": "{{steps}}", "pos": ["2", 0]}},
        "2": {"class_type": "WeirdEncoder", "inputs": {"text": "masterpiece, {{prompt}}, 8k"}},
        "3": {"class_type": "SaveImage", "inputs": {"images": ["1", 0]}},
    }
    (tmp_path / "custom.json").write_text(json.dumps(g))
    wf = load_workflow(tmp_path / "custom.json")
    graph, seed = wf.build({"prompt": "a dog"})
    assert graph["2"]["inputs"]["text"] == "masterpiece, a dog, 8k"
    assert graph["1"]["inputs"]["seed"] == seed and isinstance(seed, int)
    assert graph["1"]["inputs"]["steps"] == 20  # fallback default for a bare placeholder


def test_meta_file_targets(tmp_path):
    g = {"10": {"class_type": "Foo", "inputs": {"caption": "hi"}}, "11": {"class_type": "SaveImage", "inputs": {}}}
    (tmp_path / "foo.json").write_text(json.dumps(g))
    (tmp_path / "foo.meta.json").write_text(json.dumps({"title": "Foo", "params": {"prompt": [{"node": 10, "input": "caption"}]}}))
    wf = load_workflow(tmp_path / "foo.json")
    graph, _ = wf.build({"prompt": "yo"})
    assert graph["10"]["inputs"]["caption"] == "yo"


def test_rejects_ui_format_and_missing_prompt(tmp_path):
    (tmp_path / "ui.json").write_text(json.dumps({"nodes": [], "links": [], "version": 0.4}))
    (tmp_path / "noprompt.json").write_text(json.dumps({"1": {"class_type": "SaveImage", "inputs": {}}}))
    store = WorkflowStore(tmp_path)
    names = [w.name for w in store.list()]
    assert names == ["noprompt"]
    assert "Export (API)" in store.errors["ui"]
    with pytest.raises(WorkflowError, match="failed to load"):
        store.get("ui")
    with pytest.raises(WorkflowError, match="no prompt input"):
        store.get("noprompt").build({"prompt": "x"})
    with pytest.raises(WorkflowError, match="Unknown workflow"):
        store.get("nope")


def test_store_hot_reload(tmp_path, workflows_dir):
    store = WorkflowStore(workflows_dir)
    assert {w.name for w in store.list()} == {"flux-dev", "sdxl-basic"}
    (workflows_dir / "flux-dev.json").unlink()
    assert {w.name for w in store.list()} == {"sdxl-basic"}
