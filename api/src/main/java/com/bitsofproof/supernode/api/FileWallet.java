package com.bitsofproof.supernode.api;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bitsofproof.supernode.common.ValidationException;
import com.google.protobuf.ByteString;

public class FileWallet implements Wallet
{
	private static final SecureRandom random = new SecureRandom ();
	private transient ExtendedKey master;
	private byte[] encrypted;
	private byte[] signature;
	private String fileName;
	private long since;

	private static class NCExtendedKeyAccountManager extends ExtendedKeyAccountManager
	{
		private final String name;

		public NCExtendedKeyAccountManager (String name, long created)
		{
			super ();
			this.name = name;
			setCreated (created);
		}

		public String getName ()
		{
			return name;
		}
	}

	private final Map<String, NCExtendedKeyAccountManager> accounts = new HashMap<String, NCExtendedKeyAccountManager> ();

	public FileWallet (String fileName)
	{
		this.fileName = fileName;
	}

	public boolean exists ()
	{
		return new File (fileName).exists ();
	}

	public void init (String passphrase)
	{
		master = null;
		encrypted = new byte[32];
		random.nextBytes (encrypted);
		try
		{
			signature = ExtendedKey.createFromPassphrase (passphrase, encrypted).getMaster ().sign (encrypted);
		}
		catch ( ValidationException e )
		{
		}
	}

	public void init (String passphrase, ExtendedKey master, boolean production, long since)
	{
		this.master = master;
		this.since = since;
		try
		{
			encrypted = master.encrypt (passphrase, production);
			signature = ExtendedKey.createFromPassphrase (passphrase, encrypted).getMaster ().sign (encrypted);
		}
		catch ( ValidationException e )
		{
		}
	}

	public ExtendedKey getMaster ()
	{
		return master;
	}

	public void unlock (String passphrase) throws ValidationException
	{
		master = ExtendedKey.createFromPassphrase (passphrase, encrypted);
		if ( !master.getMaster ().verify (encrypted, signature) )
		{
			throw new ValidationException ("incorrect passphrase");
		}
		for ( NCExtendedKeyAccountManager account : accounts.values () )
		{
			account.setMaster (master.getChild (account.getMaster ().getSequence ()));
		}
	}

	public void lock ()
	{
		master = null;
		for ( ExtendedKeyAccountManager account : accounts.values () )
		{
			account.setMaster (account.getMaster ().getReadOnly ());
		}
	}

	public List<String> getAccountNames ()
	{
		List<String> names = new ArrayList<String> ();
		for ( NCExtendedKeyAccountManager account : accounts.values () )
		{
			names.add (account.getName ());
		}
		return names;
	}

	public void setFileName (String fileName)
	{
		this.fileName = fileName;
	}

	public static FileWallet read (String fileName) throws IOException, ValidationException
	{
		FileWallet wallet = new FileWallet (fileName);
		File f = new File (fileName);
		InputStream in = new FileInputStream (f);
		try
		{
			BCSAPIMessage.Wallet walletMessage = BCSAPIMessage.Wallet.parseFrom (in);
			wallet.encrypted = walletMessage.getEncryptedSeed ().toByteArray ();
			wallet.signature = walletMessage.getSignature ().toByteArray ();
			for ( BCSAPIMessage.Wallet.Account account : walletMessage.getAccountsList () )
			{
				ExtendedKey pub = ExtendedKey.parse (account.getPublicKey ());
				NCExtendedKeyAccountManager am = new NCExtendedKeyAccountManager (account.getName (), account.getCreated () * 1000);
				wallet.accounts.put (account.getName (), am);
				am.setMaster (pub);
			}
		}
		finally
		{
			in.close ();
		}
		return wallet;
	}

	public void sync (BCSAPI api, int lookAhead) throws BCSAPIException, ValidationException
	{
		for ( NCExtendedKeyAccountManager account : accounts.values () )
		{
			account.sync (api, lookAhead);
		}
	}

	@Override
	public synchronized AccountManager getAccountManager (String name)
	{
		return accounts.get (name);
	}

	@Override
	public synchronized AccountManager createAccountManager (String name) throws ValidationException
	{
		if ( accounts.containsKey (name) )
		{
			return accounts.get (name);
		}
		else
		{
			if ( master == null )
			{
				throw new ValidationException ("The wallet is locked");
			}
			NCExtendedKeyAccountManager account = new NCExtendedKeyAccountManager (name, Math.min (System.currentTimeMillis (), since));
			account.setMaster (master.getChild (accounts.size () | 0x80000000));
			accounts.put (name, account);
			return account;
		}
	}

	public void persist () throws IOException
	{
		FileOutputStream out = new FileOutputStream (fileName);
		try
		{
			BCSAPIMessage.Wallet.Builder builder = BCSAPIMessage.Wallet.newBuilder ();
			builder.setBcsapiversion (1);
			builder.setEncryptedSeed (ByteString.copyFrom (encrypted));
			builder.setSignature (ByteString.copyFrom (signature));
			for ( NCExtendedKeyAccountManager am : accounts.values () )
			{
				BCSAPIMessage.Wallet.Account.Builder ab = BCSAPIMessage.Wallet.Account.newBuilder ();
				ab.setName (am.getName ());
				ab.setCreated (am.getCreated () / 1000);
				ab.setPublicKey (am.getMaster ().getReadOnly ().serialize (true));
				builder.addAccounts (ab.build ());
			}
			builder.build ().writeTo (out);
		}
		finally
		{
			out.close ();
		}
	}
}
