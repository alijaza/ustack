package com.untzuntz.ustack.main;

import java.util.Calendar;
import java.util.Date;

import org.apache.log4j.Logger;

import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import com.untzuntz.ustack.aaa.Authentication;
import com.untzuntz.ustack.data.NotificationTemplate;
import com.untzuntz.ustack.data.UniqueReference;
import com.untzuntz.ustack.data.UserAccount;

public class UForgotPasswordSvc {
	
    static Logger           		logger               	= Logger.getLogger(UForgotPasswordSvc.class);

	public static void sendResetPassword(String requestedBy, DBObject extras, String userName, String toEmail, String toName, String templateName, String password)
	{
		UserAccount user = UserAccount.getUser(userName);
		if (user == null)
			return;
		
		try {
			
			if (password == null)
			{
				password = Authentication.generatePassword();
				user.setPassword(requestedBy, password);
				user.save(requestedBy);
			}
			
			user.put("newPassword", password);
			
			NotificationTemplate template = NotificationTemplate.getNotificationTemplate(templateName);
			UNotificationSvc svc = new UNotificationSvc();
			svc.setData("user", user);
	
			DBObject endpoint = new BasicDBObject();
			endpoint.put("destinationName", toName);
			endpoint.put("destination", toEmail);
	
			svc.sendEmail(null, template, endpoint);
		} catch (Exception er) {
			logger.error("Failed to reset password for user [" + userName + "]", er);
		}
	}
	
	public static void sendForgotPassword(String requestedBy, DBObject extras, String urlPrefix, String userName, String toEmail, String toName, String templateName)
	{
		UniqueReference uniqRef = UniqueReference.createUniqRef("pwreset");

		// set expires
		Calendar cal = Calendar.getInstance();
		cal.add(Calendar.MINUTE, 60);
		
		uniqRef.put("name", toName);
		uniqRef.put("userName", userName);
		uniqRef.put("time", new Date());
		uniqRef.put("expires", cal.getTime());
		uniqRef.put("requestedBy", requestedBy);
		uniqRef.put("url", urlPrefix + "/rdr?act=" + uniqRef.getString("actionName") + "&uid=" + uniqRef.getUid());
		if (extras != null)
			uniqRef.putAll(extras);
		uniqRef.save(requestedBy);

		NotificationTemplate template = NotificationTemplate.getNotificationTemplate(templateName);
		UNotificationSvc svc = new UNotificationSvc();
		svc.setData("resetinfo", uniqRef);

		DBObject endpoint = new BasicDBObject();
		endpoint.put("destinationName", toName);
		endpoint.put("destination", toEmail);

		svc.sendEmail(null, template, endpoint);

	}
	
}