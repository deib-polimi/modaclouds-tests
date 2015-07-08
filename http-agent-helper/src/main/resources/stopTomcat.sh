#!/bin/bash

cd /home/ubuntu/modaclouds

ID=`sudo docker ps | grep httpagent16 | awk '{print $1}'`

sudo rm -rf /home/ubuntu/logs
sudo docker cp $ID:/var/lib/tomcat7/logs /home/ubuntu/logs

cd /home/ubuntu/logs

sudo mv tomcat7/* .
sudo rm -rf tomcat7
sudo cp catalina.*.log catalina.log
sudo cp host-manager.*.log host-manager.log
sudo cp localhost.*.log localhost.log
sudo cp localhost_access_log.*.txt localhost_access_log.txt
sudo cp manager.*.log manager.log

cd /home/ubuntu/modaclouds

sudo docker kill `sudo docker ps | grep httpagent16 | awk '{print $1}'`
sudo docker rm $(sudo docker ps -a -q)

cd