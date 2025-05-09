FROM jamesnetherton/docker-atom-editor:latest

USER root

RUN apt-get update
RUN apt-get install -y wget gpg unzip
RUN apt-get install -y xvfb ffmpeg netcat xz-utils openjdk-11-jdk ssh

# FIX for docker image of Atom
RUN apt-get install -y libgbm-dev

RUN cd /tmp && curl https://nodejs.org/dist/v10.16.3/node-v10.16.3-linux-x64.tar.xz | tar -xJ
RUN cp -a /tmp/node-v10.16.3-linux-x64/* /usr
RUN rm -R /tmp/node-v10.16.3-linux-x64

RUN wget https://chromedriver.storage.googleapis.com/83.0.4103.39/chromedriver_linux64.zip
RUN unzip chromedriver_linux64.zip
RUN mv chromedriver /usr/bin
RUN rm chromedriver_linux64.zip
ENV DISPLAY=:80
ENV ELECTRON_ENABLE_LOGGING=true

RUN mkdir /root/.atom/packages -p
RUN echo "'*':\n  welcome:\n    showOnStartup: false\n  core:\n    telemetryConsent: \"no\"\n    disabledPackages: [\"github\"]\n  \"autocomplete-plus\":\n    enableAutoActivation: false" > /root/.atom/config.cson

WORKDIR /work
COPY . /work
RUN npm install
RUN npx shadow-cljs release dev --debug
RUN rm -R node_modules
RUN ln -s /work /root/.atom/packages/lazuli

CMD bash -c 'Xvfb -screen 0 1024x768x24+32 :80 & bash -m ./scripts/test'
