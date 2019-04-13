## Command Pattern Demo

A demonstration application written in java showing how to 
implement a command pattern using kafka, kstreams and avro.

### Dependencies
To run this you need to have docker and docker-compose installed.


### Running

first start the docker environment

`docker-compose up -d`

then start the app

`./gradlew bootRun`

#### Starting The App

From the root of the application directory run

```
./gradlew bootRun
```

### Demo

The demo consists of hitting some endpoints for managing vehicles.  

Swagger UI is here for trying it out http://localhost:8080/swagger-ui.html.

Zeppelin is available with a notebook at http://localhost:8888/#/notebook/2E9SUYRTK (WIP).

Kibana is also running at http://localhost:5601 .
Kibana can be used to view audit logs but requires a little setup.

`audit index: audit-idx`


