#!/bin/bash

cd /home/ubuntu/modaclouds

ID=`sudo docker ps | grep httpagent16 | awk '{print $1}'`

sudo docker kill $ID
sudo docker rm $ID

cd