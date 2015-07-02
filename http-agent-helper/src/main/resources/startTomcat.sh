#!/bin/bash
#$1=MP-IP
#$2=OBSERVER-IP
#$3=OBSERVER-PORT

PORT="8022"
IP="$1"

cd /home/ubuntu/modaclouds
sudo ./httpAgentRun.sh

sleep 10

ssh root@localhost -o StrictHostKeyChecking=no -p $PORT -i /home/ubuntu/modaclouds/id_rsa "bash /usr/local/bin/actualStartTomcat.sh $IP"

cd /home/ubuntu/http-agent-helper
bash MPloadModel $1 $2 $3

cd