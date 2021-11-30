#!/bin/bash
sudo apt-add-repository ppa:ansible/ansible.
sudo apt-get update -y
sudo apt-get install ansible -y
ansible-playbook configure-services.yaml