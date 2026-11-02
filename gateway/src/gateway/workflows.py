"""ComfyUI workflow loading and parameter injection.

Workflows are ComfyUI graphs exported in **API format** (in ComfyUI: Workflow menu >
Export (API)). Drop the `.json` file into the workflows folder.

How the gateway finds where to put the prompt, size, seed, etc. (first match wins
for each parameter):

1. A sidecar file `<name>.meta.json` with explicit targets:
       {"title": "...", "description": "...",
        "params":   {"prompt": [{"node": "6", "input": "text"}]},
        "defaults": {"width": 1024, "height": 1024}}
2. Placeholders inside the workflow, e.g. a text input set to
   "{{prompt}}" or "masterpiece, {{prompt}}", or a numeric input set to "{{seed}}".
3. Auto-detection: positive / negative text encoders are found by tracing the
   sampler's `positive` / `negative` (or Flux guider `conditioning`) links; seed,
   steps, cfg, width, height, batch_size and checkpoint are found by input name.
"""

from __future__ import annotations

import copy
import json
import logging
import random
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

log = logging.getLogger("gateway.workflows")

PARAM_TYPES: dict[str, type] = {
    "prompt": str,
    "negative_prompt": str,
    "width": int,
    "height": int,
    "batch_size": int,
    "seed": int,
    "steps": int,
    "cfg": float,
    "checkpoint": str,
}

TEXT_INPUTS = ("text", "text_g", "text_l", "t5xxl", "clip_l")
COND_INPUTS = {
    "positive", "negative", "conditioning", "conditioning_1", "conditioning_2",
    "conditioning_to", "conditioning_from", "guider", "base_conditioning",
}
PLACEHOLDER_RE = re.compile(r"\{\{\s*([a-z_]+)\s*\}\}")
MAX_SEED = 2**48
# Used only when a placeholder-targeted param has no default from the graph or meta file.
FALLBACK_DEFAULTS: dict[str, Any] = {"width": 1024, "height": 1024, "batch_size": 1, "steps": 20, "cfg": 7.0}


class WorkflowError(ValueError):
    pass


@dataclass(frozen=True)
class Target:
    node: str
    input: str
    template: str | None = None  # for "prefix {{prompt}} suffix" style strings


@dataclass
class Workflow:
    name: str
    graph: dict[str, Any]
    title: str = ""
    description: str = ""
    targets: dict[str, list[Target]] = field(default_factory=dict)
    defaults: dict[str, Any] = field(default_factory=dict)

    def public(self) -> dict:
        return {
            "id": self.name,
            "object": "workflow",
            "title": self.title or self.name,
            "description": self.description,
            "params": sorted(self.targets),
            "defaults": self.defaults,
        }

    # -------------------------------------------------------------------
    def build(self, params: dict[str, Any], rng: random.Random | None = None) -> tuple[dict, int | None]:
        """Return a ready-to-queue graph and the seed that was used (if any)."""
        rng = rng or random.SystemRandom()
        if "prompt" not in self.targets:
            raise WorkflowError(
                f"Workflow '{self.name}' has no prompt input. Put {{{{prompt}}}} in its positive text box "
                "or add a .meta.json file (see docs/workflows.md)."
            )
        fallback = {k: v for k, v in FALLBACK_DEFAULTS.items() if k in self.targets}
        values = {**fallback, **self.defaults, **{k: v for k, v in params.items() if v is not None}}
        if not str(values.get("prompt", "")).strip():
            raise WorkflowError("prompt must not be empty")
        values.setdefault("negative_prompt", "")
        seed = values.get("seed")
        if seed is None or int(seed) < 0:
            seed = rng.randrange(0, MAX_SEED)
        values["seed"] = int(seed)

        graph = copy.deepcopy(self.graph)
        for param, targets in self.targets.items():
            if param not in values:
                continue
            value = _coerce(param, values[param])
            for t in targets:
                node = graph.get(t.node)
                if node is None:
                    continue
                inputs = node.setdefault("inputs", {})
                if t.template is not None:
                    inputs[t.input] = PLACEHOLDER_RE.sub(
                        lambda m: str(_coerce(m.group(1), values.get(m.group(1), ""))) if m.group(1) in PARAM_TYPES else m.group(0),
                        t.template,
                    )
                else:
                    inputs[t.input] = value
        _strip_leftover_placeholders(graph, values)
        return graph, (values["seed"] if "seed" in self.targets else None)


def _coerce(param: str, value: Any) -> Any:
    typ = PARAM_TYPES.get(param, str)
    try:
        v = typ(value)
    except (TypeError, ValueError) as e:
        raise WorkflowError(f"{param}: expected {typ.__name__}") from e
    if param in ("width", "height"):
        v = max(64, min(4096, int(round(v / 8)) * 8))
    elif param == "steps":
        v = max(1, min(200, v))
    elif param == "batch_size":
        v = max(1, min(8, v))
    elif param == "cfg":
        v = max(0.0, min(30.0, v))
    elif param == "seed":
        v = v % MAX_SEED
    return v


def _strip_leftover_placeholders(graph: dict, values: dict) -> None:
    """Placeholders for params the request didn't set fall back to '' so ComfyUI never sees '{{x}}'."""
    for node in graph.values():
        for k, v in list(node.get("inputs", {}).items()):
            if isinstance(v, str) and PLACEHOLDER_RE.search(v):
                node["inputs"][k] = PLACEHOLDER_RE.sub(lambda m: str(values.get(m.group(1), "")), v)


def _is_link(v: Any) -> bool:
    return isinstance(v, list) and len(v) == 2 and isinstance(v[0], (str, int)) and isinstance(v[1], int)


def validate_api_format(graph: Any) -> None:
    if not isinstance(graph, dict) or not graph:
        raise WorkflowError("not a JSON object")
    if "nodes" in graph and "links" in graph:
        raise WorkflowError("this is a UI-format workflow; export it with 'Export (API)' in ComfyUI")
    for nid, node in graph.items():
        if not isinstance(node, dict) or "class_type" not in node:
            raise WorkflowError(f"node {nid!r} has no class_type; is this an API-format export?")


def detect_targets(graph: dict) -> tuple[dict[str, list[Target]], dict[str, Any]]:
    targets: dict[str, list[Target]] = {}
    defaults: dict[str, Any] = {}

    def add(param: str, t: Target, default: Any = None) -> None:
        lst = targets.setdefault(param, [])
        if t not in lst:
            lst.append(t)
            if default is not None and param not in defaults and not (isinstance(default, str) and PLACEHOLDER_RE.search(default)):
                defaults[param] = default

    # 1. placeholders
    for nid, node in graph.items():
        for inp, val in node.get("inputs", {}).items():
            if not isinstance(val, str):
                continue
            names = PLACEHOLDER_RE.findall(val)
            if not names:
                continue
            whole = PLACEHOLDER_RE.fullmatch(val.strip())
            for name in names:
                if name not in PARAM_TYPES:
                    continue
                if whole:
                    add(name, Target(str(nid), inp))
                else:
                    add(name, Target(str(nid), inp, template=val))
    placeholder_params = set(targets)

    # 2. auto-detect text encoders by tracing conditioning links
    text_hits: dict[tuple[str, str], str] = {}

    def trace(link: list, branch: str, depth: int) -> None:
        if depth > 8:
            return
        nid = str(link[0])
        node = graph.get(nid)
        if not node:
            return
        inputs = node.get("inputs", {})
        strings = [k for k in TEXT_INPUTS if isinstance(inputs.get(k), str)]
        if strings and ("TextEncode" in node.get("class_type", "") or "text" in inputs):
            for k in strings:
                key = (nid, k)
                # positive wins if a node is reachable from both branches
                if text_hits.get(key) != "prompt":
                    text_hits[key] = branch
            return
        for name, val in inputs.items():
            if name in COND_INPUTS and _is_link(val):
                trace(val, "negative_prompt" if name == "negative" else branch, depth + 1)

    for nid, node in graph.items():
        ct = node.get("class_type", "")
        if "Sampler" in ct or "Guider" in ct:
            for name, val in node.get("inputs", {}).items():
                if name in ("positive", "negative", "guider", "conditioning") and _is_link(val):
                    trace(val, "negative_prompt" if name == "negative" else "prompt", 0)

    if not text_hits:
        # fallback: plain text encoders, negatives recognised by node title
        for nid, node in graph.items():
            if "TextEncode" not in node.get("class_type", ""):
                continue
            title = str(node.get("_meta", {}).get("title", "")).lower()
            branch = "negative_prompt" if "neg" in title else "prompt"
            for k in TEXT_INPUTS:
                if isinstance(node.get("inputs", {}).get(k), str):
                    text_hits[(str(nid), k)] = branch

    for (nid, k), param in sorted(text_hits.items(), key=lambda kv: _node_sort_key(kv[0][0])):
        if param in placeholder_params:
            continue
        add(param, Target(nid, k), graph[nid]["inputs"][k])

    # 3. numeric / misc inputs by name
    for nid in sorted(graph, key=_node_sort_key):
        node = graph[nid]
        ct = node.get("class_type", "")
        inputs = node.get("inputs", {})
        for name, val in inputs.items():
            if _is_link(val):
                continue
            param = None
            if name in ("seed", "noise_seed") and isinstance(val, int):
                param = "seed"
            elif name == "steps" and isinstance(val, int) and ("Sampler" in ct or "Scheduler" in ct):
                param = "steps"
            elif name == "cfg" and isinstance(val, (int, float)) and ("Sampler" in ct or "Guider" in ct):
                param = "cfg"
            elif name in ("width", "height", "batch_size") and isinstance(val, int) and (
                (ct.startswith("Empty") and "Latent" in ct) or (ct == "ModelSamplingFlux" and name != "batch_size")
            ):
                param = name
            elif name == "ckpt_name" and isinstance(val, str):
                param = "checkpoint"
            if param and param not in placeholder_params:
                add(param, Target(str(nid), name), val)

    return targets, defaults


def _node_sort_key(nid: str) -> tuple[int, str]:
    return (int(nid), "") if str(nid).isdigit() else (1 << 30, str(nid))


def load_workflow(path: Path) -> Workflow:
    graph = json.loads(path.read_text(encoding="utf-8"))
    validate_api_format(graph)
    graph = {str(k): v for k, v in graph.items()}
    targets, defaults = detect_targets(graph)
    wf = Workflow(name=path.stem, graph=graph, targets=targets, defaults=defaults)

    meta_path = path.with_name(path.stem + ".meta.json")
    if meta_path.is_file():
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        wf.title = meta.get("title", "")
        wf.description = meta.get("description", "")
        for param, lst in (meta.get("params") or {}).items():
            if param not in PARAM_TYPES:
                raise WorkflowError(f"{meta_path.name}: unknown param {param!r}")
            ts = []
            for t in lst:
                node = str(t["node"])
                if node not in graph:
                    raise WorkflowError(f"{meta_path.name}: node {node!r} not in workflow")
                ts.append(Target(node, t["input"]))
            wf.targets[param] = ts
            cur = graph[ts[0].node].get("inputs", {}).get(ts[0].input) if ts else None
            if cur is not None and not _is_link(cur):
                wf.defaults[param] = cur
        wf.defaults.update(meta.get("defaults") or {})
    # Prompts are never useful as defaults (they'd leak the example prompt into every request).
    wf.defaults.pop("prompt", None)
    return wf


class WorkflowStore:
    def __init__(self, directory: str | Path):
        self.dir = Path(directory)
        self._cache: dict[str, Workflow] = {}
        self._mtimes: dict[str, float] = {}
        self.errors: dict[str, str] = {}

    def _scan(self) -> None:
        if not self.dir.is_dir():
            self._cache, self.errors = {}, {}
            return
        seen = set()
        for p in sorted(self.dir.glob("*.json")):
            if p.name.endswith(".meta.json"):
                continue
            seen.add(p.stem)
            meta = p.with_name(p.stem + ".meta.json")
            mtime = max(p.stat().st_mtime, meta.stat().st_mtime if meta.exists() else 0)
            if self._mtimes.get(p.stem) == mtime and (p.stem in self._cache or p.stem in self.errors):
                continue
            self._mtimes[p.stem] = mtime
            try:
                self._cache[p.stem] = load_workflow(p)
                self.errors.pop(p.stem, None)
            except (WorkflowError, json.JSONDecodeError, KeyError, TypeError) as e:
                self._cache.pop(p.stem, None)
                self.errors[p.stem] = str(e)
                log.warning("workflow %s skipped: %s", p.name, e)
        for gone in set(self._cache) - seen:
            self._cache.pop(gone, None)
        for gone in set(self.errors) - seen:
            self.errors.pop(gone, None)

    def list(self) -> list[Workflow]:
        self._scan()
        return list(self._cache.values())

    def get(self, name: str | None) -> Workflow:
        self._scan()
        if not name:
            if not self._cache:
                raise WorkflowError(f"No workflows found in {self.dir}")
            return next(iter(self._cache.values()))
        if name in self.errors:
            raise WorkflowError(f"Workflow '{name}' failed to load: {self.errors[name]}")
        wf = self._cache.get(name)
        if wf is None:
            raise WorkflowError(f"Unknown workflow '{name}'. Available: {', '.join(self._cache) or 'none'}")
        return wf
