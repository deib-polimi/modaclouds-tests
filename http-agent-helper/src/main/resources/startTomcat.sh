#!/bin/bash

cd /home/ubuntu/modaclouds
sudo ./httpAgentRun.sh

sleep 10

ID=`sudo docker ps | grep httpagent16 | awk '{print $1}'`

sudo docker exec $ID /bin/bash -c "echo MODACLOUDS_TOWER4CLOUDS_MANAGER_IP=$MODACLOUDS_TOWER4CLOUDS_MANAGER_IP >> /etc/default/tomcat7"
sudo docker exec $ID /bin/bash -c "echo MODACLOUDS_TOWER4CLOUDS_MANAGER_PORT=$MODACLOUDS_TOWER4CLOUDS_MANAGER_PORT >> /etc/default/tomcat7"
sudo docker exec $ID /bin/bash -c "echo MODACLOUDS_TOWER4CLOUDS_CLOUD_PROVIDER_ID=$MODACLOUDS_TOWER4CLOUDS_CLOUD_PROVIDER_ID >> /etc/default/tomcat7"
sudo docker exec $ID /bin/bash -c "echo MODACLOUDS_TOWER4CLOUDS_CLOUD_PROVIDER_TYPE=$MODACLOUDS_TOWER4CLOUDS_CLOUD_PROVIDER_TYPE >> /etc/default/tomcat7"
sudo docker exec $ID /bin/bash -c "echo MODACLOUDS_TOWER4CLOUDS_INTERNAL_COMPONENT_ID=$MODACLOUDS_TOWER4CLOUDS_INTERNAL_COMPONENT_ID >> /etc/default/tomcat7"
sudo docker exec $ID /bin/bash -c "echo MODACLOUDS_TOWER4CLOUDS_INTERNAL_COMPONENT_TYPE=$MODACLOUDS_TOWER4CLOUDS_INTERNAL_COMPONENT_TYPE >> /etc/default/tomcat7"
sudo docker exec $ID /bin/bash -c "echo MODACLOUDS_TOWER4CLOUDS_VM_ID=$MODACLOUDS_TOWER4CLOUDS_VM_ID >> /etc/default/tomcat7"
sudo docker exec $ID /bin/bash -c "echo MODACLOUDS_TOWER4CLOUDS_VM_TYPE=$MODACLOUDS_TOWER4CLOUDS_VM_TYPE >> /etc/default/tomcat7"

sudo docker exec $ID rm /var/lib/tomcat7/logs/*
sudo docker exec $ID service tomcat7 start

cd
