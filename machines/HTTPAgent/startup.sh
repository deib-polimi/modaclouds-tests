#!/bin/bash
#$1=DB-IP
#$2=MP-IP

bash /home/ubuntu/updateHTTPAgent

sudo bash /home/ubuntu/startHTTPAgent "$1" "$2" "$3"

source /home/ubuntu/.bashrc
bash /home/ubuntu/startImperialDC

cd
