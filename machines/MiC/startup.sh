#!/bin/bash
#$1=DB-IP
#$2=MP-IP

bash /home/ubuntu/updateEverything

sudo bash /home/ubuntu/snapshotMICStarter "$1" "$2"

source /home/ubuntu/.bashrc
bash /home/ubuntu/startImperialDC

cd
