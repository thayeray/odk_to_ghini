# odk_to_ghini

A way to get data from Open Data Kit into a Ghini Desktop formatted PostgreSQL database for managing living collections, for example in a botanical garden or arboretum. Data are collected in the field on Android devices into XLSForm instances using ODK Collect.

Note that the odk_to_ghini code is not very flexible, it assumes you are using the specific XLSForm that is included here.
Note also that the original .xlsx spreadsheet, as well as the converted .xml form and itemsets.csv files are also included. The conversion from .xlsx to .xml can be done using XLSForm Online: https://opendatakit.org/xlsform/. 

While, loading a form onto a mobile device requires ODK Aggregate, once a form is decided upon ODK Briefcase can be used to transfer files. This is the solution that is being used here, requiring only minimal use of Aggregate. 

The batch file odk_v2.bat uses the ODK Briefcase .jar from ODK, and 7-zip.
The java code uses org.apache.commons.csv.CSVFormat. These two could be avoided by rewriting the code to use XPath to parse the XML forms directly in java, and make the code flexible so that it is not specific to the XLSForm.
