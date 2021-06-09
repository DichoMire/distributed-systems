# distributed-systems

Coursework project for COMP2207 Distributed systems

It is a program that simulates a distributed file system would work over the internet on a single computer. The connections are over TCP on different ports of the machine. Files are stored and distributed over different stores - in this case folders. The system handles reads and writes of files as well as handles new stores connections and failures. 

In order to run it:

Pull the project.

Run the bat file called AllInOne.bat or AllInOneMultipleDStores.bat if you wish to try out with multiple storages.

If you wish to tweak the parameters, you need to look into:
1. controllerRunnerMultipleDstores.bat or controllerRunner.bat
2. DstoreRunner.bat DstoreRunner2.bat DstoreRunner3.bat (Only tweak DStoreRunner.bat if you want to test single storage)

Please do not change clientMainRunner.bat

Controller parameters:
(ControllerPortID, minimumNumOfStorages, timeoutForFailedOperations, RebalanceTimePeriod) Time values are in ms.

DStore parameters:
(ControllerPortID, DStorePortID, timeoutForFailedOperations, NameOfStorage)
