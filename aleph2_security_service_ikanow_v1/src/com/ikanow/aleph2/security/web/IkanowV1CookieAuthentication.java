package com.ikanow.aleph2.security.web;

import java.security.SecureRandom;
import java.util.Date;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.types.ObjectId;

import com.google.inject.Injector;
import com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IServiceContext;
import com.ikanow.infinit.e.data_model.driver.InfiniteDriver;
import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.WriteResult;


public class IkanowV1CookieAuthentication {
	public static IkanowV1CookieAuthentication instance = null;
	protected IServiceContext serviceContext = null;
	protected IManagementDbService _underlying_management_db = null;
	private DBCollection cookieDb = null; 
	private DBCollection authenticationDb = null; 
	private static final Logger logger = LogManager.getLogger(IkanowV1CookieAuthentication.class);

	private IkanowV1CookieAuthentication(IServiceContext serviceContext){
		this.serviceContext = serviceContext;
	}
	
	public static synchronized IkanowV1CookieAuthentication getInstance(Injector injector){
		if(instance == null){
			IServiceContext serviceContext = injector.getInstance(IServiceContext.class);
			instance =  new IkanowV1CookieAuthentication(serviceContext);
		}
		return instance;
	}
	
	protected void initDb(){
		if(_underlying_management_db == null) {
		_underlying_management_db = serviceContext.getService(IManagementDbService.class, Optional.empty()).get();
		}
		String cookieOptions = "security.cookies";
		cookieDb = _underlying_management_db.getUnderlyingPlatformDriver(DBCollection.class, Optional.of(cookieOptions)).get();
		String authenticationOptions = "security.authentication";
		authenticationDb = _underlying_management_db.getUnderlyingPlatformDriver(DBCollection.class, Optional.of(authenticationOptions)).get();
		}

	protected DBCollection getCookieStore(){
		if(cookieDb == null){
			initDb();
		}
	      return cookieDb;		
	}

	protected DBCollection getAuthenticationStore(){
		if(authenticationDb == null){
			initDb();
		}
	      return authenticationDb;		
	}
	
	public CookieBean createCookieByEmail(String email)
	{
		CookieBean cb = null;
		String profileId = lookupProfileIdByEmail(email);
		if(profileId!=null){
			cb = 	createCookie(profileId);
		}
		return cb;
	}

	/** TODO
	 * @return
	 */
	protected InfiniteDriver getRootDriver() {
		final InfiniteDriver driver = new InfiniteDriver();
		// create an admin cookie (get admin user by doing a query vs the DB for superuser (or whatever):true, unless we always know what it is?)
		//driver.useExistingCookie(admin_cookie);
		return driver; 
		
		// (then can use public String registerPerson(WordPressSetupPojo wpSetup, ResponseObject responseObject) to create a user)
		// FOR PASSWORD just generate a random string so it can't be guessed
		// Here's the driver code that shows what you can/need to fill in
//		Date date = new Date();
//		SimpleDateFormat formatter = new SimpleDateFormat("MMM dd, yyyy kk:mm:ss aa");
//		String today = formatter.format(date);
//		String encrypted_password;
//
//		encrypted_password = encryptWithoutEncode(password);
//
//		WordPressUserPojo wpuser = new WordPressUserPojo();
//		WordPressAuthPojo wpauth = new WordPressAuthPojo();
//
//		wpuser.setCreated(today);
//		wpuser.setModified(today);
//		wpuser.setFirstname(first_name);
//		wpuser.setLastname(last_name);
//		wpuser.setPhone(phone);
//
//		ArrayList<String> emailArray = new ArrayList<String>();
//		emailArray.add(email);
//		wpuser.setEmail(emailArray);
//
//		//wpauth.setWPUserID(email); CHANGE THIS TO USE ACTUAL WPUSERID
//		wpauth.setPassword(encrypted_password);
//		wpauth.setAccountType(accountType);
//		wpauth.setCreated(today);
//		wpauth.setModified(today);
//		
//		WordPressSetupPojo wpSetup = new WordPressSetupPojo();
//		wpSetup.setAuth(wpauth);
//		wpSetup.setUser(wpuser);
//
//		return registerPerson(wpSetup, responseObject);
		
	}
	
	protected String lookupProfileIdByEmail(String email) {
		String profileId = null;
		try {
			BasicDBObject query = new BasicDBObject();
			query.put("username", email);
			DBObject result = getAuthenticationStore().findOne(query);
			profileId = result!=null? ""+result.get("profileId"):null;
		} catch (Exception e) {
			logger.error("lookupProfileIdByEmail caught exception",e);			
		}
		return profileId;
	}

	/**
	 * Creates a new session cookie  for a user, adding
	 * an entry to our cookie table (maps cookieid
	 * to userid) and starts the clock
	 * 
	 * @param username
	 * @param bMulti if true lets you login from many sources
	 * @param bOverride if false will fail if already logged in
	 * @return
	 */
	public CookieBean createCookie(String userId)
	{
		deleteSessionCookieInDb(userId);
		CookieBean cookie = new CookieBean();
		ObjectId objectId = generateRandomId();
		
		cookie.set_id(objectId.toString()); 
		cookie.setCookieId(objectId.toString());
		Date now = new Date();
		cookie.setLastActivity(now);
		cookie.setProfileId(userId);
		cookie.setStartDate(now);
		saveSessionCookieInDb(cookie);

		return cookie;
		
	}
	private boolean saveSessionCookieInDb(CookieBean cookie) {
		int dwritten = 0;
		try {
			BasicDBObject query = new BasicDBObject();
			query.put("_id", new ObjectId(cookie.get_id()));
			query.put("profileId", new ObjectId(cookie.getProfileId()));
			query.put("cookieId", new ObjectId(cookie.getCookieId()));
			query.put("startDate", cookie.getStartDate());
			query.put("lastActivity", cookie.getLastActivity());
			if(cookie.getApiKey()!=null){
			 query.put("apiKey", cookie.getApiKey());
			}
			WriteResult result = getCookieStore().insert(query);
			dwritten = result.getN();
			
		} catch (Exception e) {
			logger.error("saveSessionCookieInDb caught exception",e);			
		}		
		return dwritten>0;
	}

	public static ObjectId generateRandomId() {
		SecureRandom randomBytes = new SecureRandom();
		byte bytes[] = new byte[12];
		randomBytes.nextBytes(bytes);
		return new ObjectId(bytes); 		
	}

	protected boolean deleteSessionCookieInDb(String userId){
		int deleted = 0;
		try {
			BasicDBObject query = new BasicDBObject();
			query.put("profileId", new ObjectId(userId));
			WriteResult result = getCookieStore().remove(query);
			deleted = result.getN();
			
		} catch (Exception e) {
			logger.error("deleteSessionCookieInDb caught exception",e);			
		}
		return deleted>0;
	}


}
