#!/bin/bash

cd /home/ubuntu/modaclouds

sudo docker kill `sudo docker ps | grep httpagent16 | awk '{print $1}'`
sudo docker rm $(sudo docker ps -a -q)

cd