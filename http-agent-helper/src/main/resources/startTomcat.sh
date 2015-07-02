#!/bin/bash
#$1=MP-IP
#$2=OBSERVER-IP
#$3=OBSERVER-PORT

PORT="8022"
IP="$1"

cd /home/ubuntu/modaclouds
sudo ./httpAgentRun.sh

sleep 10

ID=`sudo docker ps | grep httpagent16 | awk '{print $1}'`

sudo docker exec $ID /bin/bash -c "echo export MODACLOUDS_TOWER4CLOUDS_MANAGER_IP=$IP >> /root/.bashrc_httpagent"
sudo docker exec $ID rm /usr/local/tomcat/logs/*
sudo docker exec $ID bash /usr/local/tomcat/bin/startup.sh

#sudo docker exec -it $ID /bin/bash -c "echo $MODACLOUDS_TOWER4CLOUDS_MANAGER_IP"

cd /home/ubuntu/http-agent-helper
bash MPloadModel $1 $2 $3

cd