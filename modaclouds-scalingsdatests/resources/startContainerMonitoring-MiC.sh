#!/bin/bash
#$1=IP MIC

curl -X POST -k -H "Accept: application/json" -H "X-Requested-By: GlassFish REST HTML interface" -H "Authorization: Basic YWRtaW46ZGVpYi1wb2xpbWk=" https://"$1":4848/management/domain/enable-monitoring -d "target=server" -d "modules=web-container"
