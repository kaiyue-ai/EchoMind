FROM ghcr.io/aas-ee/open-web-search:latest

# open-websearch 2.1.11 disables redirects for Bing's static host, but the
# cn.bing.com/www.bing.com hop is normal in some networks. Keep Bing usable
# while retaining the shared redirect safety checks.
RUN sed -i \
    -e "s#const BING_BASE_URL = 'https://cn.bing.com/search';#const BING_BASE_URL = 'https://www.bing.com/search';#" \
    -e "s#trustedStaticHost: true#trustedStaticHost: false#" \
    /app/build/engines/bing/bing.js
