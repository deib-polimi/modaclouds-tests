#!/bin/bash
#$1=IP MIC
#$2=FOLDER

curl -X GET -k -H "Accept: application/json" -H "X-Requested-By: GlassFish REST HTML interface" -H "Authorization: Basic YWRtaW46ZGVpYi1wb2xpbWk=" https://"$1":4848/monitoring/domain/server/applications/mic-frontend-mon/server/RegisterServlet > "$2"/reg.json
curl -X GET -k -H "Accept: application/json" -H "X-Requested-By: GlassFish REST HTML interface" -H "Authorization: Basic YWRtaW46ZGVpYi1wb2xpbWk=" https://"$1":4848/monitoring/domain/server/applications/mic-frontend-mon/server/AnswerQuestionsServlet > "$2"/answ.json
curl -X GET -k -H "Accept: application/json" -H "X-Requested-By: GlassFish REST HTML interface" -H "Authorization: Basic YWRtaW46ZGVpYi1wb2xpbWk=" https://"$1":4848/monitoring/domain/server/applications/mic-frontend-mon/server/SaveAnswerServlet > "$2"/save.json

curl -X POST -k -H "Accept: application/json" -H "X-Requested-By: GlassFish REST HTML interface" -H "Authorization: Basic YWRtaW46ZGVpYi1wb2xpbWk=" https://"$1":4848/management/domain/disable-monitoring -d "target=server" -d "modules=web-container"