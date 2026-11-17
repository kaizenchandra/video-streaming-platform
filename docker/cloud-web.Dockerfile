FROM nginx:1.28-alpine
COPY web-player/ /usr/share/nginx/html/
COPY docker/nginx.cloud.conf.template /etc/nginx/templates/default.conf.template
COPY docker/require-origin.sh /docker-entrypoint.d/05-require-origin.sh
