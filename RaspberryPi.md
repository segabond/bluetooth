# Setting up bluetooth Java BlueCove service on Raspberry PI 4

## Step 1: System packages
sudo apt-get install bluez python-bluez libbluetooth-dev

## Step 2: Configure bluetooth daemon for socket support

```bash
sudo nano /etc/systemd/system/bluetooth.target.wants/bluetooth.service
```
change

`ExecStart=/usr/lib/bluetooth/bluetoothd -C`

*Optional*: If you want to change the bluetooth device name permanently, you have to create a file called `/etc/machine-info` which should have the following content:

`PRETTY_HOSTNAME=device-name`

then reload config and restart bluetoothd

```bash
sudo systemctl daemon-reload
sudo systemctl restart bluetooth
```

## Step 3: Configure socket not to require service root privilege
make sure your pi user is in the bluetooth group:

```
$ cat /etc/group | grep bluetooth
bluetooth:x:113:pi
```
1.1. If it's not, add pi to bluetooth group:
```
$ sudo usermod -G bluetooth -a pi
```
Change group of the /var/run/sdp file:
```
$ sudo chgrp bluetooth /var/run/sdp
```
To make the change persistent after reboot:

3.1. Create file /etc/systemd/system/var-run-sdp.path with the following content:

```
[Unit]
Descrption=Monitor /var/run/sdp

[Install]
WantedBy=bluetooth.service

[Path]
PathExists=/var/run/sdp
Unit=var-run-sdp.service
```
3.2. And another file, /etc/systemd/system/var-run-sdp.service:

```
[Unit]
Description=Set permission of /var/run/sdp

[Install]
RequiredBy=var-run-sdp.path

[Service]
Type=simple
ExecStart=/bin/chgrp bluetooth /var/run/sdp
```

3.3. Finally, start it all up:

```bash
sudo systemctl daemon-reload
sudo systemctl enable var-run-sdp.path
sudo systemctl enable var-run-sdp.service
sudo systemctl start var-run-sdp.path
```