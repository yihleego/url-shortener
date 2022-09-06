docker stop urlshortner
docker rm   urlshortner
docker rmi  urlshortner

docker build -t urlshortner .
docker run -d -it -p 18080:18080 --name=urlshortner urlshortner