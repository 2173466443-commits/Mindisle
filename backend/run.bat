@echo off
chcp 65001 >nul
rem 心屿 MindIsle 后端一键启动（手册 §5.9 · 需先 mvn -B -DskipTests package）
rem 编码显式指定 UTF-8：中文提示词与日志在 Windows 默认 GBK 控制台下会乱码
if not exist "target\mindisle-server-0.0.1-SNAPSHOT.jar" (
  echo [ERR] 找不到 target\mindisle-server-0.0.1-SNAPSHOT.jar，请先执行 mvn -B -DskipTests package
  exit /b 1
)
java -Dfile.encoding=UTF-8 -jar target\mindisle-server-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
echo.
echo [EXIT] 进程已退出，退出码 %ERRORLEVEL%
pause
