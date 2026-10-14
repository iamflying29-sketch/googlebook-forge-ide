"""Quick speed test for a model on this machine: cold prompt speed, warm (cached) prompt speed, generation speed.
Usage: python3 bench.py [model]"""
import json, sys, time, urllib.request
model = sys.argv[1] if len(sys.argv) > 1 else "forge-coder"
def post(path, body, timeout=900):
    req = urllib.request.Request("http://127.0.0.1:11434" + path, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=timeout))
longp = "Review this file.\n" + "\n".join(f"def f{i}(a, b):\n    return a * {i} + b\n" for i in range(60)) + "\nSummarize it in one sentence."
for label, prompt, n in (("cold prompt", longp, 32), ("warm prompt (cache)", longp + " Be brief.", 32), ("generation", "Write a Python function that reverses a linked list, with a docstring.", 160)):
    r = post("/api/generate", {"model": model, "prompt": prompt, "stream": False, "options": {"num_predict": n, "temperature": 0}})
    pe, ped, ec, ed = r.get("prompt_eval_count", 0), r.get("prompt_eval_duration", 1), r.get("eval_count", 0), r.get("eval_duration", 1)
    print(f"{label:22s}: {pe:5d} prompt tokens at {pe / ped * 1e9:7.1f} tok/s, {ec:4d} generated at {ec / ed * 1e9:5.2f} tok/s, load {r.get('load_duration', 0) / 1e9:.1f} s")
ps = json.load(urllib.request.urlopen("http://127.0.0.1:11434/api/ps", timeout=10))
for m in ps.get("models", []):
    print(f"loaded: {m['name']} using {m['size'] / 2**30:.2f} GiB, context {m.get('context_length')}")
