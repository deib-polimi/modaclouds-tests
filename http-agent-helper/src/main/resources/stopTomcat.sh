#!/bin/bash

cd /home/ubuntu/modaclouds

ID=`sudo docker ps | grep httpagent16 | awk '{print $1}'`

sudo rm -rf /home/ubuntu/logs
sudo docker cp $ID:/var/lib/tomcat7/logs /home/ubuntu/logs

cd /home/ubuntu/logs

sudo mv tomcat7/* .
sudo rm -rf tomcat7
cat catalina.*.log > catalina.log
cat host-manager.*.log > host-manager.log
cat localhost.*.log > localhost.log
cat localhost_access_log.*.txt > localhost_access_log.txt
cat manager.*.log > manager.log

cd /home/ubuntu/modaclouds

sudo docker kill `sudo docker ps | grep httpagent16 | awk '{print $1}'`
sudo docker rm $(sudo docker ps -a -q)

cd