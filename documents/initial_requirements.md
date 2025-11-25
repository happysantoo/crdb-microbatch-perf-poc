I would like to design an experiment/load testing scenarios to prove the micronbatching effectiveness of a hard hit crdb inserts scenario. It is defined and experiment parameters given below

1. create a docker-compose file to include cockroachdb , prometheus and grafana.
2. Create a non trivial table with at least 15 columns and one primary key of data type UUID.
3. design a java 21/springboot 3.5.8/gradle 9.2.1 vajrapulse load test to insert into this table one at a time. All the details on vajrapulse are availeble at https://github.com/happysantoo/vajrapulse. Use the latest version of vajrapulse (0.9.4)+
4. You can pickup the load strategy to start with 10 threads and slowly ramp to 300 threads and sustain that  till you are able to insert 1 million rows in the table. 
5. prometheus polls the springboot load process every 10 secs and inserts the metrics emitted.
6. Design a grafana dashboard which shows throughputs , latency percentiles , state of jvm , virtual threads , states of crdb as well.