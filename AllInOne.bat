del *.class
for %%i in (*.java) do if not "%%~i" == "ClientMain.java" javac "%%~i"
start cmd /k call controllerRunner.bat
timeout 1
start cmd /k call DstoreRunner.bat
timeout 1
start cmd /k call clientMainRunner.bat