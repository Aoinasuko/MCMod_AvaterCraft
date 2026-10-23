"""Local Unity MCP client. Credentials stay in memory and never in output/files."""
import json, pathlib, urllib.request, sys, os

DESCRIPTOR = pathlib.Path(os.environ['LOCALAPPDATA']) / 'UnityMCP/instances/f69e14d8553fe4f5.json'

def rpc(method, params=None):
    descriptor = json.loads(DESCRIPTOR.read_text(encoding='utf-8-sig'))
    token = next((v for k, v in descriptor.items() if 'token' in k.lower()), None)
    if not token:
        raise RuntimeError('Unity descriptor does not provide a token')
    headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream'}
    data = json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': method, 'params': params or {}}).encode()
    request = urllib.request.Request(descriptor['mcpUrl'], data=data, headers=headers)
    with urllib.request.urlopen(request, timeout=180) as response:
        raw = response.read().decode()
    if raw.startswith('event:') or raw.startswith('data:'):
        raw = next(line[5:].strip() for line in raw.splitlines() if line.startswith('data:'))
    return json.loads(raw)

if __name__ == '__main__':
    args = sys.argv[1:]
    if args[0] == 'code':
        result = rpc('tools/call', {'name': 'execute_code', 'arguments': {'code': pathlib.Path(args[1]).read_text(encoding='utf-8-sig')}})
    else:
        result = rpc(args[0], json.loads(pathlib.Path(args[1]).read_text(encoding='utf-8-sig')) if len(args) > 1 else {})
    print(json.dumps(result, ensure_ascii=True))
