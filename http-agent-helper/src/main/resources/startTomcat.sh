#!/bin/bash

PORT="8022"
IP="$1"

cd /home/ubuntu/modaclouds
sudo ./httpAgentRun.sh

sleep 10

ssh root@localhost -o StrictHostKeyChecking=no -p $PORT -i /home/ubuntu/modaclouds/id_rsa "bash /usr/local/bin/actualStartTomcat.sh $IP"

cd