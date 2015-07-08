#!/bin/bash

cd

ID=`sudo docker ps | grep httpagent16 | awk '{print $1}'`

rm -rf logs
mkdir -p logs
docker cp $ID:/var/lib/tomcat7/logs/* /home/ubuntu/logs/

cd /home/ubuntu/modaclouds

sudo docker kill `sudo docker ps | grep httpagent16 | awk '{print $1}'`
sudo docker rm $(sudo docker ps -a -q)

cd