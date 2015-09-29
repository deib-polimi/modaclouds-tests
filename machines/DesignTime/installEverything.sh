#!/bin/sh

USER=`whoami`

sudo apt-get update
sudo apt-get install -y curl
curl -sSL https://get.docker.com/ | sh
sudo usermod -aG docker $USER

cd

rm -rf space4clouds modelio

mkdir space4clouds
mkdir modelio

cd space4clouds
git clone https://github.com/deib-polimi/modaclouds-models.git

cd

sudo docker pull deibpolimi/modaclouds-designtime

sed "s|^exit|bash /home/$USER/updateEverything.sh\nbash /home/$USER/startEverything.sh\n\nexit|" </etc/rc.local >rc.local
sudo mv rc.local /etc/rc.local

echo "Please restart your bash session to be able to run the docker commands without sudo"
