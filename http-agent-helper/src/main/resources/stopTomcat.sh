#!/bin/bash

cd /home/ubuntu/modaclouds

ID=`sudo docker ps | grep httpagent16 | awk '{print $1}'`

rm -rf /home/ubuntu/logs
mkdir -p /home/ubuntu/logs
sudo docker cp $ID:/var/lib/tomcat7/logs/* /home/ubuntu/logs/

sudo docker kill `sudo docker ps | grep httpagent16 | awk '{print $1}'`
sudo docker rm $(sudo docker ps -a -q)

cd