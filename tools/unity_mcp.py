"""Local Unity MCP client. Credentials stay in memory and never in output/files."""
import argparse, json, pathlib, urllib.request, os

def descriptor_path():
    configured = os.environ.get('UNITY_MCP_DESCRIPTOR')
    if configured:
        return pathlib.Path(configured).expanduser()
    local_data = os.environ.get('LOCALAPPDATA')
    if not local_data:
        raise RuntimeError('Set UNITY_MCP_DESCRIPTOR to the Unity MCP instance descriptor')
    candidates = sorted((pathlib.Path(local_data) / 'UnityMCP/instances').glob('*.json'))
    if len(candidates) != 1:
        raise RuntimeError('Set UNITY_MCP_DESCRIPTOR explicitly when zero or multiple Unity MCP instances exist')
    return candidates[0]

def rpc(method, params=None):
    descriptor = json.loads(descriptor_path().read_text(encoding='utf-8-sig'))
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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--descriptor', help='Unity MCP instance descriptor JSON (overrides environment)')
    parser.add_argument('method', help='JSON-RPC method, or code to execute a C# file')
    parser.add_argument('payload', nargs='?', help='JSON arguments file, or C# file for code')
    args = parser.parse_args()
    if args.descriptor:
        os.environ['UNITY_MCP_DESCRIPTOR'] = args.descriptor
    if args.method == 'code':
        if not args.payload:
            parser.error('code requires a C# file')
        result = rpc('tools/call', {'name': 'execute_code', 'arguments': {'code': pathlib.Path(args.payload).read_text(encoding='utf-8-sig')}})
    else:
        result = rpc(args.method, json.loads(pathlib.Path(args.payload).read_text(encoding='utf-8-sig')) if args.payload else {})
    print(json.dumps(result, ensure_ascii=True))
