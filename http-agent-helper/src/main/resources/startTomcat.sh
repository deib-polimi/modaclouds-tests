#!/bin/bash

PORT="8022"
IP="$1"

bash /home/ubuntu/modaclouds/build.sh httpagent 16

ssh root@localhost -o StrictHostKeyChecking=no -p $PORT -i /home/ubuntu/modaclouds/id_rsa "bash /usr/local/bin/actualStartTomcat.sh $IP"
