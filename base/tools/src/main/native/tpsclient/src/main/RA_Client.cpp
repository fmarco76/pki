// --- BEGIN COPYRIGHT BLOCK ---
// This library is free software; you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation;
// version 2.1 of the License.
// 
// This library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
// Lesser General Public License for more details.
// 
// You should have received a copy of the GNU Lesser General Public
// License along with this library; if not, write to the Free Software
// Foundation, Inc., 51 Franklin Street, Fifth Floor,
// Boston, MA  02110-1301  USA 
// 
// Copyright (C) 2007 Red Hat, Inc.
// All rights reserved.
// --- END COPYRIGHT BLOCK ---

#include <stdio.h>
#include <string.h>
#include <stdarg.h>

#include "prinrval.h"
#include "prmem.h"
#include "prsystem.h"
#include "plstr.h"
#include "prio.h"
#include "prprf.h"
#include "pk11func.h"
#include "nss.h"

#include "main/NameValueSet.h"
#include "main/Util.h"
#include "main/RA_Client.h"
#include "main/RA_Msg.h"
#include "main/RA_Token.h"
#include "authentication/AuthParams.h"
#include "apdu/APDU_Response.h"
#include "apdu/Initialize_Update_APDU.h"
#include "apdu/External_Authenticate_APDU.h"
#include "apdu/Set_Pin_APDU.h"
#include "msg/RA_Begin_Op_Msg.h"
#include "msg/RA_End_Op_Msg.h"
#include "msg/RA_Login_Request_Msg.h"
#include "msg/RA_Login_Response_Msg.h"
#include "msg/RA_Extended_Login_Request_Msg.h"
#include "msg/RA_Extended_Login_Response_Msg.h"
#include "msg/RA_Token_PDU_Request_Msg.h"
#include "msg/RA_Token_PDU_Response_Msg.h"
#include "msg/RA_New_Pin_Request_Msg.h"
#include "msg/RA_New_Pin_Response_Msg.h"
#include "msg/RA_SecureId_Request_Msg.h"
#include "msg/RA_SecureId_Response_Msg.h"
#include "msg/RA_ASQ_Request_Msg.h"
#include "msg/RA_ASQ_Response_Msg.h"
#include "msg/RA_Status_Update_Request_Msg.h"
#include "msg/RA_Status_Update_Response_Msg.h"

static PRFileDesc *m_fd_debug = (PRFileDesc *) NULL;

/**
 * Constructs a RA client that talks to RA.
 */
RA_Client::RA_Client ()
{
  /* default global variables */
  m_vars.Add ("ra_host", "air");
  m_vars.Add ("ra_port", "8000");
  m_vars.Add ("ra_uri", "/nk_service");
}

/**
 * Destructs this RA client.
 */
RA_Client::~RA_Client ()
{
  if (m_fd_debug != NULL)
    {
      PR_Close (m_fd_debug);
      m_fd_debug = NULL;
    }
}

static void
Output (const char *fmt, ...)
{
  va_list ap;
  va_start (ap, fmt);
  printf ("Output> ");
  vprintf (fmt, ap);
  printf ("\n");
  va_end (ap);
}

void
RA_Client::Debug (const char *func_name, const char *fmt, ...)
{
  PRTime now;
  const char *time_fmt = "%Y-%m-%d %H:%M:%S";
  char datetime[1024];
  PRExplodedTime time;

  if (m_fd_debug == NULL)
    return;
  va_list ap;
  va_start (ap, fmt);
  now = PR_Now ();
  PR_ExplodeTime (now, PR_LocalTimeParameters, &time);
  PR_FormatTimeUSEnglish (datetime, 1024, time_fmt, &time);
  PR_fprintf (m_fd_debug, "[%s] %s - ", datetime, func_name);
  PR_vfprintf (m_fd_debug, fmt, ap);
  va_end (ap);
  PR_Write (m_fd_debug, "\n", 1);
}

int
RA_Client::OpHelp (NameValueSet * params)
{
  Output ("Available Operations:");
  Output ("op=debug filename=<filename> - enable debugging");
  Output ("op=help");
  Output
    ("op=ra_enroll uid=<uid> pwd=<pwd> num_threads=<number of threads> secureid_pin=<secureid_pin> keygen=<true|false> - Enrollment Via RA");
  Output
    ("op=ra_reset_pin uid=<uid> pwd=<pwd> num_threads=<number of threads> secureid_pin=<secureid_pin> new_pin=<new_pin> - Reset Pin Via RA");
  Output
    ("op=ra_update uid=<uid> pwd=<pwd> num_threads=<number of threads> secureid_pin=<secureid_pin> new_pin=<new_pin> - Reset Pin Via RA");
  Output ("op=token_set <name>=<value> - Set Token Value");
  Output ("op=token_status - Print Token Status");
  Output ("op=var_get name=<name> - Get Value of Variable");
  Output ("op=var_list - List All Variables");
  Output ("op=var_set name=<name> value=<value> - Set Value to Variable");

  return 1;
}

static void
GetBuffer (Buffer & buf, char *output, int len)
{
  int i;

  output[0] = '\0';
  for (i = 0; i < (int) buf.size (); ++i)
    {
      sprintf (output, "%s%02x", output, ((BYTE *) buf)[i]);
    }
}

static BYTE
ToVal (char c)
{
  if (c >= '0' && c <= '9')
    {
      return c - '0';
    }
  else if (c >= 'A' && c <= 'Z')
    {
      return c - 'A' + 10;
    }
  else if (c >= 'a' && c <= 'z')
    {
      return c - 'a' + 10;
    }

  /* The following return is needed to suppress compiler warnings on Linux. */
  return 0;
}

static Buffer *
ToBuffer (char *input)
{
  int len = strlen (input) / 2;
  BYTE *buffer = NULL;

  buffer = (BYTE *) malloc (len);
  if (buffer == NULL)
    {
      return NULL;
    }

  for (int i = 0; i < len; i++)
    {
      buffer[i] = (ToVal (input[i * 2]) * 16) + ToVal (input[i * 2 + 1]);
    }
  Buffer *j;
  j = new Buffer (buffer, len);

  if (buffer != NULL)
    {
      free (buffer);
      buffer = NULL;
    }

  return j;
}

int
RA_Client::OpTokenStatus (NameValueSet * params)
{
  int i;
  char output[2048];

  Output ("life_cycle_state : '%x'", m_token.GetLifeCycleState ());
  Output ("pin : '%s'", m_token.GetPIN ());
  GetBuffer (m_token.GetAppletVersion (), output, 2048);
  Output ("app_ver : '%s' (%d bytes)", output,
	  m_token.GetAppletVersion ().size ());
  Output ("major_ver : '%x'", m_token.GetMajorVersion ());
  Output ("minor_ver : '%x'", m_token.GetMinorVersion ());
  GetBuffer (m_token.GetCUID (), output, 2048);
  Output ("cuid : '%s' (%d bytes)", output, m_token.GetCUID ().size ());
  GetBuffer (m_token.GetMSN (), output, 2048);
  Output ("msn : '%s' (%d bytes)", output, m_token.GetMSN ().size ());
  GetBuffer (m_token.GetKeyInfo (), output, 2048);
  Output ("key_info : '%s' (%d bytes)", output,
	  m_token.GetKeyInfo ().size ());
  GetBuffer (m_token.GetAuthKey (), output, 2048);
  Output ("auth_key : '%s' (%d bytes)", output,
	  m_token.GetAuthKey ().size ());
  GetBuffer (m_token.GetMacKey (), output, 2048);
  Output ("mac_key : '%s' (%d bytes)", output, m_token.GetMacKey ().size ());
  GetBuffer (m_token.GetKekKey (), output, 2048);
  Output ("kek_key : '%s' (%d bytes)", output, m_token.GetKekKey ().size ());

  /* print all the public/private keys */
  if (params->GetValue ("print_cert") != NULL)
    {
      for (i = 0; i < m_token.NoOfCertificates (); i++)
	{
	  CERTCertificate *cert = m_token.GetCertificate (i);
	  Output ("Certificate #%d: '%s'", i, cert->nickname);
	}
    }

  if (params->GetValue ("print_private") != NULL)
    {
      for (i = 0; i < m_token.NoOfPrivateKeys (); i++)
	{
	  SECKEYPrivateKey *key = m_token.GetPrivateKey (i);
#if 0
	  SECKEYPublicKey *pubKey = SECKEY_ConvertToPublicKey (key);
	  Buffer modulus = Buffer (pubKey->u.rsa.modulus.data,
				   pubKey->u.rsa.modulus.len);
	  Buffer exponent = Buffer (pubKey->u.rsa.publicExponent.data,
				    pubKey->u.rsa.publicExponent.len);
#endif
	  Output ("Private Key #%d: '%s'", i,
		  PK11_GetPrivateKeyNickname (key));
	}
    }

  return 1;
}

int
RA_Client::OpTokenSet (NameValueSet * params)
{
  if (params->GetValue ("cuid") != NULL)
    {
      Buffer *CUID = ToBuffer (params->GetValue ("cuid"));
      m_token.SetCUID (*CUID);
      if (CUID != NULL)
	{
	  delete CUID;
	  CUID = NULL;
	}
    }
  if (params->GetValue ("msn") != NULL)
    {
      Buffer *MSN = ToBuffer (params->GetValue ("msn"));
      m_token.SetMSN (*MSN);
      if (MSN != NULL)
	{
	  delete MSN;
	  MSN = NULL;
	}
    }
  if (params->GetValue ("app_ver") != NULL)
    {
      Buffer *Version = ToBuffer (params->GetValue ("app_ver"));
      m_token.SetAppletVersion (*Version);
      if (Version != NULL)
	{
	  delete Version;
	  Version = NULL;
	}
    }
  if (params->GetValue ("major_ver") != NULL)
    {
      m_token.SetMajorVersion (atoi (params->GetValue ("major_ver")));
    }
  if (params->GetValue ("minor_ver") != NULL)
    {
      m_token.SetMinorVersion (atoi (params->GetValue ("minor_ver")));
    }
  if (params->GetValue ("key_info") != NULL)
    {
      Buffer *KeyInfo = ToBuffer (params->GetValue ("key_info"));
      m_token.SetKeyInfo (*KeyInfo);
      if (KeyInfo != NULL)
	{
	  delete KeyInfo;
	  KeyInfo = NULL;
	}
    }
  if (params->GetValue ("auth_key") != NULL)
    {
      Buffer *Key = ToBuffer (params->GetValue ("auth_key"));
      m_token.SetAuthKey (*Key);
      if (Key != NULL)
	{
	  delete Key;
	  Key = NULL;
	}
    }
  if (params->GetValue ("mac_key") != NULL)
    {
      Buffer *Key = ToBuffer (params->GetValue ("mac_key"));
      m_token.SetMacKey (*Key);
      if (Key != NULL)
	{
	  delete Key;
	  Key = NULL;
	}
    }
  if (params->GetValue ("kek_key") != NULL)
    {
      Buffer *Key = ToBuffer (params->GetValue ("kek_key"));
      m_token.SetKekKey (*Key);
      if (Key != NULL)
	{
	  delete Key;
	  Key = NULL;
	}
    }
  return 1;
}

int
RA_Client::OpVarSet (NameValueSet * params)
{
  m_vars.Add (params->GetValue ("name"), params->GetValue ("value"));
  Output ("%s: '%s'", params->GetValue ("name"),
	  m_vars.GetValue (params->GetValue ("name")));
  return 1;
}

int
RA_Client::OpVarDebug (NameValueSet * params)
{
  if (m_fd_debug != NULL)
    {
      PR_Close (m_fd_debug);
      m_fd_debug = NULL;
    }
  m_fd_debug = PR_Open (params->GetValue ("filename"),
			PR_RDWR | PR_CREATE_FILE | PR_APPEND, 400 | 200);
  return 1;
}

int
RA_Client::OpVarGet (NameValueSet * params)
{
  char *value = m_vars.GetValue (params->GetValue ("name"));
  Output ("%s: '%s'", params->GetValue ("name"), value);

  return 1;
}

int
RA_Client::OpVarList (NameValueSet * params)
{
  int i;
  char *name;

  for (i = 0; i < m_vars.Size (); i++)
    {
      name = m_vars.GetNameAt (i);
      Output ("%s: '%s'", name, m_vars.GetValue (name));
    }
  return 1;
}
