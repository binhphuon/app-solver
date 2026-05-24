@echo off
chcp 65001 >nul
title Push to GitHub

set REPO_URL=https://github.com/binhphuon/app-solver.git
set PROJECT_DIR=%~dp0

echo ================================================
echo   MultiCaptcha Solver - Push to GitHub
echo ================================================
echo.

cd /d "%PROJECT_DIR%"

:: Kiem tra git da cai chua
git --version >nul 2>&1
if errorlevel 1 (
    echo [LOI] Git chua duoc cai dat!
    echo Tai git tai: https://git-scm.com/download/win
    pause
    exit /b 1
)

:: Init git neu chua co
if not exist ".git" (
    echo [1/5] Khoi tao git repository...
    git init
    git branch -M main
    git remote add origin %REPO_URL%
    echo      Done.
) else (
    echo [1/5] Git da duoc khoi tao truoc do.
    :: Dam bao remote dung
    git remote set-url origin %REPO_URL% 2>nul || git remote add origin %REPO_URL%
)

:: Stage tat ca file
echo.
echo [2/5] Stage tat ca file...
git add .

:: Hien thi so file se commit
for /f %%i in ('git diff --cached --name-only ^| find /c /v ""') do set FILE_COUNT=%%i
echo      %FILE_COUNT% file(s) se duoc commit.

:: Commit voi timestamp
echo.
echo [3/5] Tao commit...
set TIMESTAMP=%date:~6,4%-%date:~3,2%-%date:~0,2% %time:~0,2%:%time:~3,2%
git commit -m "build: update %TIMESTAMP%" 2>&1
if errorlevel 1 (
    echo      Khong co thay doi moi de commit.
)

:: Push
echo.
echo [4/5] Push len GitHub...
echo      URL: %REPO_URL%
echo.
git push -u origin main 2>&1
if errorlevel 1 (
    echo.
    echo [LOI] Push that bai!
    echo Kiem tra:
    echo   1. Da dang nhap GitHub chua? Chay: git config --global credential.helper manager
    echo   2. Co quyen push vao repo nay khong?
    echo   3. Ket noi mang co on khong?
    echo.
    pause
    exit /b 1
)

echo.
echo [5/5] Hoan thanh!
echo ================================================
echo   Xem Actions tai:
echo   https://github.com/binhphuon/app-solver/actions
echo ================================================
echo.
echo Nhan phim bat ky de dong...
pause >nul
