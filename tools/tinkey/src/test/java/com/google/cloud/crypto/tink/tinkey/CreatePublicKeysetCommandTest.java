// Copyright 2017 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////

package com.google.cloud.crypto.tink.tinkey;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.cloud.crypto.tink.CommonProto.EcPointFormat;
import com.google.cloud.crypto.tink.CommonProto.EllipticCurveType;
import com.google.cloud.crypto.tink.CommonProto.HashType;
import com.google.cloud.crypto.tink.EciesAeadHkdfProto.EciesAeadHkdfPrivateKey;
import com.google.cloud.crypto.tink.Registry;
import com.google.cloud.crypto.tink.TestUtil;
import com.google.cloud.crypto.tink.TinkProto.KeyData;
import com.google.cloud.crypto.tink.TinkProto.KeyStatusType;
import com.google.cloud.crypto.tink.TinkProto.KeyTemplate;
import com.google.cloud.crypto.tink.TinkProto.Keyset;
import com.google.cloud.crypto.tink.TinkProto.OutputPrefixType;
import com.google.cloud.crypto.tink.aead.AeadFactory;
import com.google.cloud.crypto.tink.aead.GcpKmsAeadKeyManager;
import com.google.cloud.crypto.tink.hybrid.EciesAeadHkdfPublicKeyManager;
import com.google.cloud.crypto.tink.hybrid.HybridDecryptFactory;
import com.google.cloud.crypto.tink.hybrid.HybridEncryptFactory;
import com.google.cloud.crypto.tink.mac.MacFactory;
import com.google.cloud.crypto.tink.signature.PublicKeySignFactory;
import com.google.cloud.crypto.tink.signature.PublicKeyVerifyFactory;
import com.google.cloud.crypto.tink.subtle.ServiceAccountGcpCredentialFactory;
import com.google.protobuf.TextFormat;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@code CreatePublicKeysetCommand}.
 */
@RunWith(JUnit4.class)
public class CreatePublicKeysetCommandTest {
  private static final int AES_KEY_SIZE = 16;
  private static final int HMAC_KEY_SIZE = 20;

  @Before
  public void setUp() throws Exception {
    AeadFactory.registerStandardKeyTypes();
    MacFactory.registerStandardKeyTypes();
    HybridDecryptFactory.registerStandardKeyTypes();
    HybridEncryptFactory.registerStandardKeyTypes();
    PublicKeySignFactory.registerStandardKeyTypes();
    PublicKeyVerifyFactory.registerStandardKeyTypes();

    Registry.INSTANCE.registerKeyManager(
        GcpKmsAeadKeyManager.TYPE_URL,
        new GcpKmsAeadKeyManager(
            new ServiceAccountGcpCredentialFactory(TestUtil.SERVICE_ACCOUNT_FILE)));
  }

  @Test
  public void testCreate() throws Exception {
    // Create a keyset that contains a single private key of type EciesAeadHkdfPrivateKey.
    int ivSize = 12;
    int tagSize = 16;
    EllipticCurveType curve = EllipticCurveType.NIST_P256;
    HashType hashType = HashType.SHA256;
    EcPointFormat pointFormat = EcPointFormat.UNCOMPRESSED;
    KeyTemplate demKeyTemplate = TestUtil.createAesCtrHmacAeadKeyTemplate(AES_KEY_SIZE, ivSize,
        HMAC_KEY_SIZE, tagSize);
    byte[] salt = "some salt".getBytes("UTF-8");
    KeyTemplate keyTemplate = TestUtil.createEciesAeadHkdfKeyTemplate(curve, hashType, pointFormat,
        demKeyTemplate, salt);
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    String outFormat = "TEXT";
    String awsKmsMasterKeyValue = null;
    String gcpKmsMasterKeyValue = null;
    File credentialFile = null;
    CreateCommand.create(outputStream, outFormat, credentialFile, keyTemplate,
        gcpKmsMasterKeyValue, awsKmsMasterKeyValue);
    Keyset.Builder builder = Keyset.newBuilder();
    TextFormat.merge(outputStream.toString(), builder);
    Keyset privateKeyset = builder.build();
    KeyData privateKeyData = privateKeyset.getKey(0).getKeyData();
    EciesAeadHkdfPrivateKey privateKey = EciesAeadHkdfPrivateKey.parseFrom(
        privateKeyData.getValue());

    // Create the public keyset.
    ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
    String inFormat = "TEXT";
    outputStream.reset();
    CreatePublicKeysetCommand.create(outputStream, outFormat, inputStream, inFormat,
        credentialFile);
    builder = Keyset.newBuilder();
    TextFormat.merge(outputStream.toString(), builder);
    Keyset publicKeyset = builder.build();
    assertEquals(1, publicKeyset.getKeyCount());
    assertEquals(publicKeyset.getPrimaryKeyId(), publicKeyset.getKey(0).getKeyId());
    assertEquals(publicKeyset.getPrimaryKeyId(), privateKeyset.getPrimaryKeyId());

    // Check the public key inside the public keyset.
    assertTrue(publicKeyset.getKey(0).hasKeyData());
    assertEquals(KeyStatusType.ENABLED, publicKeyset.getKey(0).getStatus());
    assertEquals(OutputPrefixType.TINK, publicKeyset.getKey(0).getOutputPrefixType());

    KeyData publicKeyData = publicKeyset.getKey(0).getKeyData();
    assertEquals(EciesAeadHkdfPublicKeyManager.TYPE_URL,
        publicKeyData.getTypeUrl());
    assertEquals(KeyData.KeyMaterialType.ASYMMETRIC_PUBLIC, publicKeyData.getKeyMaterialType());
    assertArrayEquals(privateKey.getPublicKey().toByteArray(),
        publicKeyData.getValue().toByteArray());
  }
}
