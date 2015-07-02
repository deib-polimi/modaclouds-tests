#!/bin/bash

cd /home/ubuntu/modaclouds
sudo docker kill `sudo docker ps | grep httpagent16 | awk '{print $1}'`

cd