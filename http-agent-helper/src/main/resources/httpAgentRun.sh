#!/bin/sh

IP="$1"

sudo docker run -d -p 80:80 -p 443:443 -p 8022:22 -p 8080:8080 --env MODACLOUDS_TOWER4CLOUDS_MANAGER_IP=$IP httpagent16

echo Use port 80 to connect to web server and 8022 to ssh into container