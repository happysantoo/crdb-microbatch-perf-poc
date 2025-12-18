
I would like to do a complete rewrite of the application with the following considerations 

1. upgrade vortex to 0.0.2 and taking advantage of the nrew callback features.
2. Upgrade to 0.9.5 version of vajrapulse and use AdaptiveLoadPattern to go as much  TPS as possible and run the test say for an hour.
3. No need to have the monitoring thread and look for the counter to reach a million. 
4. Rewrite the grafana dashobaord completely showing the new individual item metrics along with the overall thourghput , also include new panels to capture vajprapulse adaptive load metrics as well. 
5. Keep the code as simple as possible so it can be shown as a demo exmaple of how vortex can be used in high speed heavy processing use cases. 
6. capture other jvm metrics as well and show it on grafana dashboard.