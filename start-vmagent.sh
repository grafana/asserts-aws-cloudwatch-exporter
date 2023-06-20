#! /bin/sh

if [ "${SCRAPE_OVER_TLS}" = 'true' ]; then
  echo "Agent will scrape remote targets over https"
  /vmagent-prod -envflag.enable -promscrape.config=/etc/agent-scrape-config-https.yml
else
  echo "Agent will scrape remote targets over http"
  /vmagent-prod -envflag.enable -promscrape.config=/etc/agent-scrape-config.yml
fi
