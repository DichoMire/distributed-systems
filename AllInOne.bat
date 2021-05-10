del *.class
javac @classes
start cmd /k call controllerRunner.bat
timeout 1
start cmd /k call DstoreRunner.bat
timeout 1
start cmd /k call clientMainRunner.bat