del *.class
javac @classes
start cmd /k call controllerRunnerMultipleDstores.bat
timeout 1
start cmd /k call DstoreRunner.bat
timeout 1
start cmd /k call DstoreRunner2.bat
timeout 1
start cmd /k call DstoreRunner3.bat
timeout 1
start cmd /k call clientMainRunner.bat