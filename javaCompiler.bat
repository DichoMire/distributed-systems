del *.class
for %%i in (*.java) do if not "%%~i" == "ClientMain.java" javac "%%~i"