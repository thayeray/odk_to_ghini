package odk_to_ghini;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Properties;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

// TODO Note that this could be done without using ODK Briefcase, by just using XPath to parse the xml.
//   this is implemented in the java extensions javax.xml parsers, javax.xml.xpath and org.w3c.dom
//   e.g.:  https://www.journaldev.com/1194/java-xpath-example-tutorial
public class BriefcaseToGhini
{ 	public static final String TIMESTAMP = "MMM dd, yyyy hh:mm:ss";
	public static final String DATE_3LETTER_MONTH = "MMM dd, yyyy";
	public static final String DATE_YEAR_FIRST = "yyyy-MM-dd";
	private static final String RTE_QUERY = "SELECT species_id FROM species_note WHERE category = '<rare_threatened_endangered_Mpa>'" 
											+ " ORDER BY species_id;";
	private static final String GENUS_OTHER_QUERY = "SELECT s.id FROM genus g INNER JOIN species s ON g.id = s.genus_id"
													+ " WHERE g.genus = 'other';";
	private static final String SPECIES_SP_QUERY = "SELECT id FROM species WHERE sp = 'sp.' ORDER BY id;";
	private static final String IN_LIST_QUERY = "SELECT note, code FROM accession_note a INNER JOIN accession b ON a.accession_id = b.id" 
											+ " WHERE category = '<meta-instanceID>';";
	private static final String MAX_CODE_QUERY = "SELECT tay_max_accession_code(?);";		
	private static final String LOCATION_ID_QUERY = "SELECT tay_get_location_id_of_coords(?, 4326)";
	private static final int ACCESSION_ID_POSITION = 14;
	private static final String[] ACCESSION_NOTE = {"initials;volother_initials", "taxon_note", "deviceid", "meta-instanceID"};
	private static final String[] PLANT_NOTE = {"sign_number", "tree_tag_number", "comments", 
		"dbh", "condition_rating", "insect_disease", "insect_disease_note", "add_maintenance", "addmaintenance_detail",   
		"canopy_dominance", "relative_height", "planted_date",
		"photo", "photo_type", "phototype_detail", "photo2", "photo2_type", "photo2type_detail", "photo3", "photo3_type", "photo3type_detail",
		"offset_ft_mag_n", "gps_coord-Accuracy", "gps_coord-Longitude;gps_coord-Latitude", 
		"exgps_coordinates_external", "exgps_name;exgpsname_detail", "exgps_offset_ft_mag_n", "exgps_point_number_external",  		
		};//"deviceid", "meta-instanceID" <-- add back for resurvey 
	private static String csv_filename = "odk_briefcase_export.csv"; //"test_11_13_2018.csv"; //"odk_csv_resurvey_then_3.csv"; //
	private static String host = "localhost"; 
	private static String database = "database_name"; 
	private static String port = "5432";
	private static String user = "username"; // 
	private static String password = "password";
	private static boolean checkAndExit = false;
	private static LinkedList<Added> addedList = new LinkedList<Added>();	// used to check for duplicate records (meta-instanceID's)
	private static LinkedList<Added> sessionList = new LinkedList<Added>();	// list of records added during this session.
	private static LinkedList<Integer> rte = new LinkedList<Integer>();		// Rare Threatened and Endangered
	private static LinkedList<Integer> speciesSp = new LinkedList<Integer>();	// used to check if the species.sp value is 'sp.'
	private static Connection conn;
	private static int year = 0;	// checks if records are different years from one another, so acc_code counter can be reset
	private static int acc_code = 0; // accession code counter
	
	public static void main(String[] args) throws IOException, SQLException
	{	// TODO make a new exception class that is easier for non-Java people to read 
		// TODO make more comprehensive list of test data and test the program again.
		// TODO add .gpx file, decision logic and add position data to geometry column.
		// TODO change connection so that it requires a commit --> no partial uploads to database.
//args = new String[]{"-U","","-d","","-pw","","-f",""};	 // use this to test command line argument parsing in Eclipse:
	boolean args_read = readArgs(args);
	if (!args_read)
		System.out.println("WARNING! Command Line Arguments not read!");
	else // command line arguments read successfully
{	if (!checkAndExit) // -c switch NOT present in command line arguments.
	{	System.out.println("\n\n==============================================================================="
						   + "\n======================== Briefcase to Ghini PostgreSQL ========================"
						   + "\n===============================================================================");
		File briefcaseCSV = new File(csv_filename);
	
		CSVParser bcParse = null;
		if (!briefcaseCSV.exists())
			throw new IOException("ERROR! The ODK Briefcase output csv_file: " + csv_filename + " could not be found.");
		else bcParse = CSVParser.parse(briefcaseCSV, Charset.defaultCharset(), CSVFormat.DEFAULT.withHeader());		
		if (connectToDB())
			System.out.println("Connection successful to " + database + ". The network timeout is: " + conn.getNetworkTimeout() + "ms");
		else{	throw new SQLException("ERROR! Connection failed:\nhost: " + host + "\ndatabase: " 
								+ database + "\nuser: " + user + "\npassword: " + password);
			}
		boolean rtePopulated = populateRTE();
		if (rtePopulated)
			System.out.println("RTE list populated successfully.");
		else { 	System.out.println("WARNING! RTE list failed to populate."
									+ "\n\t'Accession Private' will not be assigned correctly");
		}
		int genusOther = getGenusOther();
		boolean speciesSpPopulated = populateSpeciesSp();
		if (speciesSpPopulated & genusOther > 0)
			System.out.println("Genus 'other' and species 'sp.' list populated successfully.");
		else 
		{	if (!speciesSpPopulated & genusOther == 0)
				System.out.println("WARNING! Genus 'other' and species 'sp.' list did not populate."
									+ "\n\tNo warning will be issued to manually enter a new genus or species.");
			if (!speciesSpPopulated & genusOther > 0)
				System.out.println("WARNING! Species 'sp.' list did not populate."
									+ "\n\tNo warning will be issued to manually enter a new species.");
			if (speciesSpPopulated & genusOther == 0)
				System.out.println("WARNING! Genus 'other' did not populate."
									+ "\n\tNo warning will be issued to manually enter a new genus.");
		}
		boolean addedListPopulated = populateAddedList();
		if (addedListPopulated)
			System.out.println("The list of ODK form unique IDs populated successfully.");
		else 
			System.out.println("WARNING! The list of ODK form unique IDs FAILED to populate!"
								+ "\n\tRepeat forms may be added to Ghini!");
		
		String metaInstanceID = "", acc_insrt = "", recvd_type = "";
		int accession_id = 0, count = 0, count2 = 0, count3 = 0, count4 = 0, count5 = 0; 
		for (CSVRecord bcRecord : bcParse) // start of CSVRecord for loop
		{	String initials = "", accession_code = "";
			boolean resurvey = false;
			if (bcRecord.get("resurvey") != null)
				resurvey = (bcRecord.get("resurvey")).equalsIgnoreCase("YES");
			if (bcRecord.get("meta-instanceID") != null)
			{	metaInstanceID = bcRecord.get("meta-instanceID");			
				if(!addedList.contains(new Added(metaInstanceID))) // check if record already added during this run.
				{	Date date_accd = null, date_recvd = null; //, planted_date = null;
					int species = 0, yearc = 0;
					
					
					// Prepare INSERT INTO accession, get the values and create the SQL statement
					date_accd = dateFromODK(bcRecord.get("start"), BriefcaseToGhini.TIMESTAMP);
					yearc = getYear(date_accd);
					if (bcRecord.get("received_date") != null)
						date_recvd = dateFromODK(bcRecord.get("received_date"), DATE_3LETTER_MONTH);
					if (bcRecord.get("species") != null)
						species = (int) Math.floor(Double.valueOf(bcRecord.get("species")));		
					if (bcRecord.get("recvd_type") != null)
						recvd_type = bcRecord.get("recvd_type");
							
					if(!resurvey)
					{	accession_code = getNextAccessionCode(yearc);
						Added toAdd = new Added(metaInstanceID,accession_code);
						addedList.add(toAdd);
						sessionList.add(toAdd);
						boolean priv = rte.contains(Integer.valueOf(species));
						acc_insrt = "INSERT INTO accession (code, date_accd, date_recvd, private, prov_type, "
							 + "quantity_recvd, recvd_type, species_id, wild_prov_status, _created, _last_updated) "
							 + "VALUES (?, ?::date, ?::date, ?::boolean, ?, 1::int, ?, ?::int, ?, now(), now());";
						String date_accd_s = dateToSQLstring(date_accd, DATE_YEAR_FIRST);
						String[] params = {accession_code, date_accd_s, dateToSQLstring(date_recvd, DATE_YEAR_FIRST), 
								Boolean.valueOf(priv).toString(), bcRecord.get("prov_type"), bcRecord.get("recvd_type"), 
								Integer.valueOf(species).toString(), bcRecord.get("wild_prov_status")};	
						// INSERT INTO accession
						LinkedList<Integer> acc = executeInsert(acc_insrt, params, true, ACCESSION_ID_POSITION);
						if (acc != null)
						{	accession_id = acc.getFirst();
							count = count + acc.size();
						}
						String taxon_note = "";
						if (bcRecord.get("taxon_note") != null)
							taxon_note = bcRecord.get("taxon_note");
						if (species == genusOther)
							System.out.println("WARNING! A new GENUS must be MANUALLY ENTERED into Ghini, for "
											+ "\n\t'Accession Code': " + accession_code + "\tDetail: " + taxon_note);
						if (speciesSp.contains(species))
							System.out.println("WARNING! A new SPECIES must be MANUALLY ENTERED into Ghini, for " 
											+ "\n\t'Accession Code': " + accession_code + "\tDetail: " + taxon_note);
						// INSERT INTO accession_note 			
						for (String category : ACCESSION_NOTE)
						{	if(bcRecord.get(getFirstMappedArgument(category)) != null)
							{	if(!(bcRecord.get(getFirstMappedArgument(category)).equals("")))
								{	LinkedList<Integer> acc_n = new LinkedList<Integer>();
									String accession_note_insert = "INSERT INTO accession_note (date, \"user\", accession_id, category,"
												+ " note, _created, _last_updated) VALUES (?::date, ?, ?::int, ?, ?, now(), now());";
									if(category.startsWith("initials"))
										initials = splitAndCombineODKSelectAndDetails(category, bcRecord);;
									String[] ANI_params_a = {date_accd_s, initials, Integer.toString(accession_id), "<initials>", initials};
									String[] ANI_params_b = {date_accd_s, initials, Integer.toString(accession_id), "<" + category + ">", 
														bcRecord.get(getFirstMappedArgument(category))};
									if(category.startsWith("initials")) 
									{	acc_n = executeInsert(accession_note_insert, ANI_params_a, false, -1);				
										count2 = count2 + acc_n.size();
									}
									else 
									{	acc_n = executeInsert(accession_note_insert, ANI_params_b, false, -1);		
										count2 = count2 + acc_n.size();
									}
								}
							}
						}
		
						// INSERT INTO plant
						// TODO allow for more than one plant per accession --> plant.code.
						// TODO add memorial to the survey
						// TODO add quantity planted to survey form
						String acc_type = "", coord = "";
						int location_id = 0, plant_id = 0;
						if (bcRecord.get("planting") != null)
							acc_type = getAccType(bcRecord.get("planting"), recvd_type);
						else acc_type = "Other";
						if (bcRecord.get("gps_coord-Latitude") != null & bcRecord.get("gps_coord-Longitude") != null)
							coord = bcRecord.get("gps_coord-Longitude") + " " + bcRecord.get("gps_coord-Latitude");
						else if(bcRecord.get("exgps_coordinates_external") != null)
							coord = bcRecord.get("exgps_coordinates_external");
						location_id = getLocationID(coord);
						String plant_insert = "INSERT INTO plant (code, acc_type, memorial, quantity, accession_id, location_id, _created,"
											+ " _last_updated) VALUES ('1', ?, false::boolean, 1::int, ?::int, ?::int, now(), now())";
						String[] pi_params = {acc_type, Integer.toString(accession_id), Integer.toString(location_id)};
						LinkedList<Integer> plant_ids = new LinkedList<Integer>();
						plant_ids = BriefcaseToGhini.executeInsert(plant_insert, pi_params, true, 7);
						if (plant_ids != null)
						{	plant_id = (int) plant_ids.getFirst();
							count3 = count3 + plant_ids.size();
						}
						
						// INSERT INTO source
						String source_contact = "", source_detail = "";
						int source_detail_id = 0;
						if (bcRecord.get("source_detail") != null)
							source_detail = bcRecord.get("source_detail");
						else source_detail = "No detail given.";
						if (bcRecord.get("source_contact") != null)
						{	source_contact = bcRecord.get("source_contact");
							try
							{	source_detail_id = Integer.valueOf(source_contact);
								String SOURCE_INSERT = "INSERT INTO source (accession_id, source_detail_id, _created, _last_updated)"
														+ " VALUES (?::int, ?::int, now(), now())";	
								String[] s_params = {Integer.toString(accession_id), Integer.toString(source_detail_id)};
								LinkedList<Integer> source_n = new LinkedList<Integer>();
								source_n = BriefcaseToGhini.executeInsert(SOURCE_INSERT, s_params, false, 0);
								count4 = count4 + source_n.size();
							}	catch(NumberFormatException e)
							{ 	if (source_contact.equalsIgnoreCase("NULL"))
								{	System.out.println("WARNING! Source Contact 'Garden Propagation' must be MANUALLY ENTERED into"
											+ "\n\tGhini for 'Accession Code': " + accession_code + ", for the ODK form start time:"
											+ "\n\t'start': " + bcRecord.get("start") + ", and  'deviceid': " + bcRecord.get("deviceid")
											+ "\n\t Detail: " + source_detail);
								}	else if(source_contact.equalsIgnoreCase("other"))
								{	System.out.println("WARNING! Source Contact 'other', a new Source Contact must be MANUALLY ENTERED into Ghini,"
											+ "\n\tand that source must be added manually for 'Accession Code': " + accession_code
											+ "\n\tfor the ODK form start time 'start': " + bcRecord.get("start") + "\n\t and  'deviceid': " 
											+ bcRecord.get("deviceid") + "\n\t Detail: " + source_detail);
								}
							}	catch(Exception e)
							{	e.printStackTrace();
							}
						}
		
						// INSERT INTO plant_note				
						for(String category:PLANT_NOTE)
						{	if(bcRecord.get(getFirstMappedArgument(category)) != null)
							{	if(!(bcRecord.get(getFirstMappedArgument(category)).equals("")))
								{	String note = "";
									note = splitAndCombineODKSelectAndDetails(category, bcRecord);
									String plant_note_insert = "INSERT INTO plant_note (date, \"user\", plant_id, category, note," 
														+ " _created, _last_updated) VALUES (?::date, ?, ?::int, ?, ?, now(), now());";
									String[] pn_params = {date_accd_s, initials, Integer.toString(plant_id), 
															get32charCategoryName(category), note};
									LinkedList<Integer> plant_note_count = new LinkedList<Integer>();
									plant_note_count = executeInsert(plant_note_insert, pn_params, false, -1);		
									count5 = count5 + plant_note_count.size();
								}
							}
						}
						
						
					}    // end of !resurvey
					else // start resurvey
					{	String resurvey_acc_code = "", resurvey_plant_code = "";
						if(bcRecord.get("accession") != null)
							resurvey_acc_code = bcRecord.get("accession");
						if(bcRecord.get("plant_code") != null)
							resurvey_plant_code = bcRecord.get("plant_code");
						System.out.println("WARNING! ALL RESURVEY information must be MANUALLY ENTERED into Ghini, " 
										+ "\n\tfor 'Accession Code': " + resurvey_acc_code + ", 'Plant Code': "+ resurvey_plant_code 
										+ " \n\tfor the ODK start time 'start': " + bcRecord.get("start") 
												+ "\n\t and 'deviceid': " + bcRecord.get("deviceid")); 
						addedList.add(new Added(metaInstanceID, resurvey_acc_code));
						if (year == 0)  // have to initialize acc_code counter when resurvey is the first record in the csv
						{	accession_code = getNextAccessionCode(yearc);
							acc_code--;  // down iterate accession code counter b/c record is not inserted
						} // TODO set up UPDATE query for resurvey.
					}	// end resurvey
					year = yearc;
				}	
				else
				{	if(!resurvey)
					System.out.println("WARNING! Possible REPEATED FORM was NOT ADDED to Ghini!"
						+ "\n\tmeta-instanceID: " + metaInstanceID 
						+ "\n\tThe first occurrence was added as accession_code: " + addedList.get(addedList.indexOf(new Added(metaInstanceID))).getAccessionCode());
					else System.out.println("WARNING! Possible REPEATED FORM was NOT ADDED to Ghini!" 
											+ "\n\tmeta-instanceID: " + metaInstanceID 
							+ "\n\tThe first occurrence is a RESURVEY of accession_code: " + addedList.get(addedList.indexOf(new Added(metaInstanceID))).getAccessionCode());
				}
			}
		} // end of for loop
		System.out.println("Total INSERTED into accession: " + count);
		System.out.println("Total INSERTED into accession_note: " + count2);
		System.out.println("Total INSERTED into plant: " + count3);
		System.out.println("Total INSERTED into source: " + count4);
		System.out.println("Total INSERTED into plant_note: " + count5);
		System.out.println("\nThe following forms were added to Ghini:\naccn.code\tmeta-instanceID");
		for (Added cur : sessionList)
			System.out.println(cur);
		//conn.commit();
		conn.close();
	} else // -c switch checks database connection and exits
	{	System.out.println(connectToDB());
		conn.close();
	}	
}// end command line arguments read successfully
	} // end of main
	
	private static boolean readArgs(String[] args)
	{	int i = 0, len = args.length;
		try
		{	while (i <= len - 1)
			{	if (i == len - 1)
				{	if (args[len - 1] != null)
					{	if (args[len - 1].equals("-c"))
							checkAndExit = true;
					}
					else 
					{ 	System.out.println(help);
						return false;
					}
				} else;
				if (args[i].startsWith("-") & i < len - 1)
				{	switch (args[i])
					{	case "-f":
							csv_filename = args[++i];
							break;
						case "-h":
							host = args[++i];
							break;
						case "-d":
							database = args[++i];
							break;
						case "-p":
							port = args[++i];
						case "-U":
							user = args[++i];
							break;
						case "-pw":
							password = args[++i];
							break;
						/*case "-c":
						{	if (args[++i] != null)
							{	if (args[i].startsWith("t"))
									checkAndExit = true;
								else checkAndExit = false;
							}
						}*/
						default:
						{	System.out.println(help);
							return false;
						}
					}
				}
				else i++;
			}
			return true;
		} catch (Exception e)
		{	e.printStackTrace();
			return false;
		}	
	}
	
	private static String help =
			  "\n================================================================================"
			+ "\nThis program reads the CSV file output from ODK Briefcase, and inserts the lines"
			+ "\n  read from forms into the designated Ghini database in PostgreSQL format."
			+ "\nThe following arguments can do the listed tasks:"
			+ "\n\tArguments in brackets are [optional].\n"
			+ "\n-?\t--> show this help message" 
			+ "\n-f\t--> file path to the CSV file output from ODK Briefcase"
			+ "\n-h\t--> the host URL or 'localhost' for the Ghini database." 
			+ "\n-p\t--> the port number on which the Ghini PostgreSQL database listens.\n\t\tDefault port: 5432."
			+ "\n-d\t--> the name of the Ghini database."
			+ "\n-U\t--> the username for the Ghini database."
			+ "\n-pw\t--> the password for the Ghini database."
			+ "\n-c\t--> MUST be last switch! Checks database connection and exit. t = connected"
			+ "\n================================================================================";

	
	/**
	 * 
	 * @param host
	 * @param database
	 * @param user
	 * @param password
	 * @return boolean 
	 */
	private static boolean connectToDB()
	{	try	// load JDBC driver 
		{	Class.forName("org.postgresql.Driver");
		} catch (ClassNotFoundException e) 
		{	e.printStackTrace();
		}
		// create connection to database and execute queries
		try
		{	String url = "jdbc:postgresql://" + host + "/" + database; // localhost/gwynns_from_osm
			Properties props = new Properties();
			if(user != null)
				props.setProperty("user",user);
			if(password != null)
				props.setProperty("password", password);
			if(port != null)
				props.setProperty("port", port);
			conn = DriverManager.getConnection(url, props); // void java.sql.Connection.setAutoCommit(boolean autoCommit) throws SQLException
			//conn.setAutoCommit(false);
			return conn.isValid(conn.getNetworkTimeout());
		}catch(Exception e)
		{	e.printStackTrace();
			return false;
		}	
	}
	
	
	public static String resultSetAsString(ResultSet rs)
	{	String result = "";
		try
		{
			ResultSetMetaData md = rs.getMetaData();
			int cols = md.getColumnCount();
			for(int i = 1; i <= cols; i++)		// print out the field names
			{	if (i < cols) result += md.getColumnLabel(i) + "\t";
				else result += md.getColumnLabel(i);
			} result += "\n";
			while (rs.next())					// print out the results from the query
			{	for(int i = 1; i <= cols; i++)
				{	if (i < cols) result += rs.getString(i) + "\t";
					else result += rs.getString(i);
				} result += "\n";
			}
		}
		catch(SQLException e)
		{	result += e.toString();
			e.printStackTrace();}
		return result;
	}
	
	/*public static String toSQLstring(String toBeFormatted, String format, boolean isFirst)
	{	String result = "";
		switch(format)
		{	case DATE_YEAR_FIRST:
				
				break;
			default:
				;
		}
		return result;
	}*/
	
	public static Date dateFromODK(String odkDate, String dateFormat)
	{	DateFormat df = getDateFormat(dateFormat);
		try {	return df.parse(odkDate);
		} catch (ParseException e) {	
				return null;
		}
	}
	
	public static String dateToSQLstring(Date date, String dateFormat)
	{	DateFormat df = getDateFormat(dateFormat);
		String result = "";		
		if (date != null)
			result = df.format(date);
		else result = null;
		return result;
	}
	
	private static DateFormat getDateFormat(String dateFormat)
	{	return new SimpleDateFormat(dateFormat, Locale.ENGLISH);
	}
	
	/**
	 * For use when combining ODK fields into one. Method looks for a semicolon and returns the first part of the category (field name).
	 * @param category
	 * @return
	 */
	private static String getFirstMappedArgument(String category)
	{	if(category.contains(";"))
		{	String[] parts = category.split(";");
			if(parts[0] != null)
				return (String) parts[0];
			else return "category not formed correctly";
		} else return category;
	}
	
	private static String get32charCategoryName(String category)
	{	String pt1 = "", pt2 = "";
		int pt1_len = 0, pt2_len = 0;
		if(category.contains(";"))
		{	String[] parts = category.split(";");
			if(parts[0] != null & parts[1] != null)
			{	pt1 = parts[0];
				pt1_len = pt1.length();
				pt2 = parts[1];
				pt2_len = pt2.length();
			}
			else return "category not formed correctly";
			if (pt1_len + pt2_len <= 31)
				return "<" + pt1 + "_" + pt2 + ">";
			else
			{	if(pt1.contains("-"))
					pt1 = pt1.substring(0, pt1.indexOf("-") + 4);
				if(pt2.contains("-"))
					pt2 = pt2.substring(pt2.indexOf("-") + 1, pt2.indexOf("-") + 4);
				pt1 += pt2;
				if (pt1.length() <= 30)
					return "<" + pt1 + ">";
				else return "<" + pt1.substring(0, 29) + ">";
			}
		} else return "<" + category + ">";
	}
	
	/**
	 * Returns the combined result for two semicolon separated field names. Removes 'Other','other', and 'volunteer' from
	 * 		the result mapped to the first field name and adds the result mapped to the second at the end of the first string.
	 * @param selectAndDetails
	 * @param csvRecord
	 * @return
	 */
	public static String splitAndCombineODKSelectAndDetails(String selectAndDetails, CSVRecord csvRecord)
	{	String toAdd = "", pt1 = "", pt2 = "";
		if(selectAndDetails.contains(";"))
		{	String[] parts = selectAndDetails.split(";");
			if(parts[0] != null)
				pt1 = parts[0];
			if(parts[1] != null)
				pt2 = parts[1];
//if(selectAndDetails.contains("gps_coord-L")) System.out.print("pt1: "+pt1+"\tpt2: " + pt2);
			if(csvRecord.get(pt1) != null & csvRecord.get(pt2) != null)
			{	toAdd = combineODKSelectAndDetails(csvRecord.get(pt1),csvRecord.get(pt2));
//if(selectAndDetails.contains("gps_coord-L")) System.out.print("\thas both pt1: " + csvRecord.get(pt1) + "\t pt2: " + csvRecord.get(pt2));
			}
		} else {	if(csvRecord.get(selectAndDetails) != null)
						toAdd = csvRecord.get(selectAndDetails);
		}	
//if(selectAndDetails.contains("gps_coord-L")) System.out.println("\treturned: "+toAdd);
		return toAdd;
	}
	
	/**
	 * Use for combining the result of an ODK 'select' list with its follow up text entry 'details'. 
	 * @param select: The result from an ODK 'select one' or 'select multiple' list. 'Other', 'other' and 'volunteer' are removed.   
	 * @param details: Appended to the end of the modified 'select' string.
	 * @return: the combined string 
	 */
	public static String combineODKSelectAndDetails(String select, String details)
	{	String result = select;
		if(result.toLowerCase().contains("other") | result.contains("volunteer"))
		{	result = result.replace("other", "");
			result = result.replace("Other", "");
			result = result.replace("volunteer", "");
			result = result.trim();
			result = (result.trim() + " " + details.trim()).trim();
		}	else result = (select.trim() + " " + details.trim()).trim();
		return result;
	}
	
	private static String getAccType(String planting, String recvd_type)
	{	String result = "";
		if (planting.equalsIgnoreCase("YES"))
		{	if (recvd_type.equals("PLNT"))
				result = "Plant";
			else if (recvd_type.equals("SEED"))
				result = "Seed";
			else result = "Other";
		} else result = "Other";
		return result;		
	}
	
	public static int getLocationID(String coord)
	{	int result = 0;
		try {	PreparedStatement ps1 = conn.prepareStatement(LOCATION_ID_QUERY);
				ps1.setString(1, coord);
				ResultSet rs1 = ps1.executeQuery();	
				if (rs1.next())	
					if (rs1.getString(1) != null)
						result = Integer.valueOf(rs1.getString(1));
	//return locationID;
		} catch (SQLException e) {
			e.printStackTrace();
	//return locationID;
		}
		return result;
	}
	
	public static String getNextAccessionCode(int yearc)
	{	String result = "";
		ResultSet rs2;
		try {	if(year != yearc)
				{	PreparedStatement ps2 = conn.prepareStatement(MAX_CODE_QUERY);
					ps2.setInt(1, yearc);
					rs2 = ps2.executeQuery();
					if (rs2.next())
					{	acc_code = rs2.getInt(1);//Integer.valueOf(
					} else { acc_code = 0;}
				}
				int dig = 3 - (int) Math.floor(Math.log10(++acc_code));
				for (int i = 0; i < dig; i++)
					result = "0" + result;
				result = Integer.toString(yearc) + "." + result + Integer.toString(acc_code);
				return result;
			} catch (SQLException e) {
				e.printStackTrace();
				return null;
			}
	}
	
	public static int getYear(Date date)
	{	Calendar calendar = new GregorianCalendar();
		calendar.setTime(date);
		return calendar.get(Calendar.YEAR);
	}
	
	private static boolean populateAddedList()
	{	try{	Statement st1 = conn.createStatement();
				ResultSet rs3 = st1.executeQuery(IN_LIST_QUERY);
				while (rs3.next())
				{	addedList.add(new Added(rs3.getString("note"), rs3.getString("code")));
				}
				return true;
		} catch (SQLException e) {
				e.printStackTrace();
				return false;
			}
		
		
		
	}
	
	private static boolean populateRTE()
	{	try {	Statement st1 = conn.createStatement();
    			ResultSet rs3 = st1.executeQuery(RTE_QUERY);	
    			while (rs3.next())					
    			{	rte.add(Integer.valueOf(rs3.getString(1)));
    			}
    			return true;
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		}
	}
	
	private static int getGenusOther()
	{	try {	Statement st1 = conn.createStatement();
				ResultSet rs3 = st1.executeQuery(GENUS_OTHER_QUERY);	
				if (rs3.next())					
				{	return Integer.valueOf(rs3.getInt("id"));
				}
				return 0;
		} catch (SQLException e) {
			e.printStackTrace();
			return 0;
		}
		 //
	}
	
	private static boolean populateSpeciesSp()
	{	try {	Statement st1 = conn.createStatement();
    			ResultSet rs3 = st1.executeQuery(SPECIES_SP_QUERY);	
    			while (rs3.next())					
    			{	speciesSp.add(Integer.valueOf(rs3.getString(1)));
    			}
    			return true;
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		}
	}
	
	@SuppressWarnings("unused")
	private static PreparedStatement loadPreparedStatementParams(String sql, String[] params, boolean returnKeys)
	{	PreparedStatement ps = null;
		try {	// insert the accessions, and then get the generated accession id
			int number = 0;
			if (returnKeys)
				ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
			else ps = conn.prepareStatement(sql);
			for (String param:params)
			{	ps.setString(++number, param);
			}
			return ps;
		} catch (SQLException e){e.printStackTrace(); return ps;}
	}
	
	/**
	 * Executes the INSERT 'sql', parameterized with 'params'.
	 *   Adapted from: http://www.postgresqltutorial.com/postgresql-jdbc/insert/
	 * @param sql INSERT statement with ? for each parameter in the 'params' string array, cast ? to data type if necessary (?::date).  
	 * @param params string array, in the same order as the 'sql' parameter.
	 * @param returnKeys true if you want the auto-generated id returned
	 * @param keyToReturn column position of the returned id
	 * @return LinkedList<Integer>: if 'returnKeys' is true: returns the auto-generated id's, if false: returns the number of inserts.
	 */
	private static LinkedList<Integer> executeInsert(String sql, String[] params, boolean returnKeys, int keyToReturn)
	{	PreparedStatement ps = null;
		int insertCount = 0;
		LinkedList<Integer> ids = new LinkedList<Integer>();
		try {	// insert the accessions, and then get the generated accession id
			int number = 0;
			if (returnKeys)
				ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
			else ps = conn.prepareStatement(sql);
			for (String param:params)
			{	ps.setString(++number, param);
			}
//System.out.println("prepared statement:\n\t" + ps);			
			insertCount = ps.executeUpdate();
			if (insertCount > 0 & returnKeys)
			{	try {	ResultSet rs = ps.getGeneratedKeys(); // get the accession id
						if (rs.next()) {	
							ids.add(rs.getInt(keyToReturn));				
						}
				} catch (SQLException e) {	e.printStackTrace();}
			} else ids.add(Integer.valueOf(insertCount));
			return ids;
		} catch (SQLException e){e.printStackTrace(); return ids;}
	}
	
	@SuppressWarnings("unused")
	private static ResultSet executeQuery(String query_string, String[] params)
	{	try {	if(conn.isValid(conn.getNetworkTimeout()))
				{	Statement st2 = conn.createStatement();
					return st2.executeQuery(query_string);
				} else {	
					connectToDB();
					Statement st3 = conn.createStatement();
					return st3.executeQuery(query_string);
				}
		} catch (SQLException e) {
			e.printStackTrace();
			return null;
		}
		
	}
}