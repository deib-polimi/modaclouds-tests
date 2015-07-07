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

sudo docker exec $ID /bin/bash -c "echo MODACLOUDS_TOWER4CLOUDS_MANAGER_IP=$IP >> /etc/default/tomcat7"
sudo docker exec $ID rm /var/lib/tomcat7/logs/*
sudo docker exec $ID service tomcat7 start

echo "export MODACLOUDS_TOWER4CLOUDS_MANAGER_IP=$IP" >> ~/.bashrc_httpagent

# TODO: remove these if updating the installHTTPAgent script on the vm
echo "export MODACLOUDS_TOWER4CLOUDS_INTERNAL_COMPONENT_ID=http-agent-helper" >> ~/.bashrc_httpagent
echo "export MODACLOUDS_TOWER4CLOUDS_INTERNAL_COMPONENT_TYPE=HTTPAgentHelper" >> ~/.bashrc_httpagent
echo "export MODACLOUDS_TOWER4CLOUDS_VM_ID=HTTPAgent" >> ~/.bashrc_httpagent
echo "export MODACLOUDS_TOWER4CLOUDS_VM_TYPE=Frontend" >> ~/.bashrc_httpagent
echo "export MODACLOUDS_TOWER4CLOUDS_CLOUD_PROVIDER_ID=amazon" >> ~/.bashrc_httpagent
echo "export MODACLOUDS_TOWER4CLOUDS_CLOUD_PROVIDER_TYPE=IaaS" >> ~/.bashrc_httpagent

#cd /home/ubuntu/http-agent-helper
#bash MPloadModel $1 $2 $3

cd