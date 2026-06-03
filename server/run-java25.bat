@echo off
REM NeoFolia is compiled for Java 25. This launcher uses the local Java 25 install directly.
"C:\Program Files\Java\jdk-25.0.2+10\bin\java.exe" @user_jvm_args.txt @libraries/net/neoforged/neoforge/26.1.2.67-neofolia/win_args.txt %* nogui
pause
