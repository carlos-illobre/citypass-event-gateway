"""Demo end-to-end: todo evento de negocio entra por Event Gateway."""
import argparse
import os
import time
import requests

FQN="com.citypass.movilidad.SecurityDemo"

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--normal",type=int,default=100);parser.add_argument("--anomalous",type=int,default=5)
    parser.add_argument("--auth-url",default=os.getenv("AUTH_SERVICE_URL","http://localhost:8083"));parser.add_argument("--gateway-url",default=os.getenv("EVENT_GATEWAY_URL","http://localhost:8080"))
    parser.add_argument("--security-url",default="http://localhost:8084");parser.add_argument("--timeout",type=int,default=90)
    args=parser.parse_args()
    token=requests.post(f"{args.auth_url}/oauth/token",auth=("grupo3","grupo3"),data={"grant_type":"client_credentials"},timeout=10).json()["access_token"]
    headers={"Authorization":f"Bearer {token}","Content-Type":"application/json"}
    schema={"name":"SecurityDemo","fields":[{"name":"stationId","type":"string"},{"name":"duration","type":"int"},{"name":"items","type":{"type":"array","items":"string"}}]}
    response=requests.get(f"{args.gateway_url}/api/v1/event-types/{FQN}",headers=headers,timeout=10)
    if response.status_code==404:
        requests.post(f"{args.gateway_url}/api/v1/event-types",headers=headers,json=schema,timeout=10).raise_for_status()
    else:response.raise_for_status()
    publish_url=f"{args.gateway_url}/api/v1/event-types/{FQN}/events"
    for i in range(args.normal):
        requests.post(publish_url,headers=headers,json={"stationId":f"station-{i%3}","duration":20+i%5,"items":["normal"]},timeout=10).raise_for_status()
    deadline=time.monotonic()+args.timeout
    while time.monotonic()<deadline:
        models=requests.get(f"{args.security_url}/api/v1/security/models",params={"topic":FQN},timeout=10).json()
        if models["total"]:break
        time.sleep(1)
    else:raise TimeoutError("Security no formó el modelo dentro del timeout")
    requests.post(publish_url,headers=headers,json={"stationId":"station-normal","duration":22,"items":["normal"]},timeout=10).raise_for_status()
    for i in range(args.anomalous):
        requests.post(publish_url,headers=headers,json={"stationId":f"extreme-{i}","duration":2_000_000_000-i,"items":["extreme"]*100},timeout=10).raise_for_status()
    print(f"Demo completa: baseline={args.normal}, normal posterior=1, anómalos={args.anomalous}")
    print("Dashboard: http://localhost:8501")

if __name__=="__main__":main()
