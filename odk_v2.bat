@echo off
echo =============================== & echo.== Import ODK Forms to Ghini == & echo.===============================
cd "C:\Path\Tp\Your\ODK_folder"

Rem assuming that your Postgres database is on port 5432
set HOST=host_details
set USER=user_name
set DATABASE=ghini_database_name

Rem Get password with the text masked. From post by 'unclemeat':
Rem https://stackoverflow.com/questions/664957/can-i-mask-an-input-text-in-a-bat-file
powershell -Command $pword = read-host "Enter password" -AsSecureString ; $BSTR=[System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($pword) ; [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($BSTR) > .tmp.txt & set /p PASSWORD=<.tmp.txt & del .tmp.txt

Rem Check the connection to the database
java -jar BriefcaseToGhini.jar -pw %PASSWORD% -h %HOST% -d %DATABASE% -U %USER% -c > temp.txt
set /p VAR=<temp.txt
if not "%VAR%"=="true" (
 echo Connection to %DATABASE% failed: & echo. host: %HOST% & echo. user: %USER%
 goto thefinish
)
if "%VAR%"=="true" (echo Able to connect to %DATABASE%)

Rem Get zip file path:
set /p ZIP="Zipped ODK folder path (drag and drop & press enter): "
Rem set EXT=%ZIP:~-4% echo %EXT%
set FP=%ZIP:~1,-1%

Rem Check if the file exists and that its extension is zip
if not exist "%FP%" (
 echo Your entry: %ZIP%, does not look like a zip file!
 goto thefinish
)
if exist "%FP%" echo File found
set EXT=%FP:~-3%
if not "%EXT%"=="zip" if not "%EXT%"=="ZIP" (
 echo Your entry: %ZIP%, does not look like a zip file!
 goto thefinish
)
set BOO=F
if "%EXT%"=="zip" set BOO=T
if "%EXT%"=="ZIP" set BOO=T
if "%BOO%"=="T" echo That looks like a zip file.

Rem Unzip and move 'odk' folder into place. Note that 7zip seems to require full paths and single line command.
"C:\Program Files\7-Zip\7z.exe" x %ZIP% -o"C:\Users\Thayer Young\Desktop\ODK_test\ODK_Folders_from_Android\odk_uz_temp" -y
move ODK_Folders_from_Android\odk_uz_temp\odk ODK_Folders_from_Android

Rem Prepare for OSK Briefcase. Move and rename the old ODK Briefcase Storage folder and exported csv files
move "ODK_Briefcase_imported_form_data\ODK Briefcase Storage" ODK_Briefcase_imported_form_data\old
Rem from: https://stackoverflow.com/questions/4984391/cmd-line-rename-file-with-date-and-time
rename "ODK_Briefcase_imported_form_data\old\ODK Briefcase Storage" ^
	ODK_BS_renamed_%date:~10,4%%date:~7,2%%date:~4,2%-%time:~0,2%%time:~3,2%
rename ODK_Briefcase_exported_form_data\*.csv odk_csv_renamed_%date:~10,4%%date:~7,2%%date:~4,2%-%time:~0,2%%time:~3,2%.csv
move ODK_Briefcase_exported_form_data\*.csv ODK_Briefcase_exported_form_data\old_csv

Rem ODK Briefcase part 1 -> pull forms from collect (Android) and rename the pulled Android folder 
java -jar ODK-Briefcase-v1.12.2.jar ^
 --pull_collect ^
 --odk_directory ODK_Folders_from_Android/odk ^
 --storage_directory ODK_Briefcase_imported_form_data
rename ODK_Folders_from_Android\odk ^
	odk_imported_%date:~10,4%%date:~7,2%%date:~4,2%-%time:~0,2%%time:~3,2%

Rem ODK Briefcase part 2 -> export forms to csv
java -jar ODK-Briefcase-v1.12.2.jar ^
 --export ^
 --export_directory ODK_Briefcase_exported_form_data ^
 --export_filename test_11_28_2018.csv ^
 --form_id LCD_FieldSurveyForm_v1.3 ^
 --storage_directory ODK_Briefcase_imported_form_data

Rem Insert the csv records into Ghini 
java -jar BriefcaseToGhini.jar -f ODK_Briefcase_exported_form_data\*.csv ^
 -pw %PASSWORD% -h %HOST% -d %DATABASE% -U %USER%
goto thefinish

:thefinish
echo ======== & echo.==done== & echo.========
pause
pause
pause
pause