#!/bin/bash
#$1=IP HTTPAgent
#$2=FOLDER
#$3=KEY

ssh -i $3 -o StrictHostKeyChecking=no ubuntu@"$1" 'bash /home/ubuntu/http-agent-helper/stopTomcat.sh'
