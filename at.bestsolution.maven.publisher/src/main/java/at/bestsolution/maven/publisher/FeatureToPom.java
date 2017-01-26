/*******************************************************************************
 * Copyright (C) 2017  BestSolution.at
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301  USA
 *******************************************************************************/
package at.bestsolution.maven.publisher;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.google.common.io.Files;

/**
 * Convert a feature to a pom
 */
public class FeatureToPom {
	private final File file = Files.createTempDir();
	private File featureJar;

	public FeatureToPom(File featureJar) {
		this.featureJar = featureJar;
	}

	public void publish() throws Exception {
		OsgiToMaven.unzipRepository(featureJar, file);
		SAXParserFactory instance = SAXParserFactory.newInstance();
		SAXParser parser = instance.newSAXParser();
		SaxHandlerImpl dh = new SaxHandlerImpl();
		parser.parse(new File(file,"feature.xml"), dh);

		try(FileOutputStream out = new FileOutputStream(new File(file,"poms/"+dh.id+".xml"));
				OutputStreamWriter w = new OutputStreamWriter(out);) {
			w.write("<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
			w.write("	xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
			w.write("	<modelVersion>4.0.0</modelVersion>\n");
			w.write("	<groupId>at.bestsolution.eclipse</groupId>\n");
			w.write("	<artifactId>"+dh.id+"</artifactId>\n");
			w.write("	<version>"+OsgiToMaven.toPomVersion(dh.version)+"</version>\n");
			w.write("	<packaging>pom</packaging>\n");
			w.write("	<dependencies>\n");
			dh.dep.stream()
				.filter( d -> !d.id.endsWith("source"))
				.forEach( d -> {
					try {
						w.write("		<dependency>\n");
						w.write("			<groupId>at.bestsolution.eclipse</groupId>\n");
						w.write("			<artifactId>"+d.id+"</artifactId>\n");
						w.write("			<version>"+OsgiToMaven.toPomVersion(d.version)+"</version>\n");
						w.write("		</dependency>\n");
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				});
			w.write("	</dependencies>\n");
			w.write("</project>");
			w.close();
		}

	}

	static class SaxHandlerImpl extends DefaultHandler {
		private String id;
		private String version;
		private List<Dep> dep = new ArrayList<>();

		@Override
		public void startElement(String uri, String localName, String qName, Attributes attributes)
				throws SAXException {
			super.startElement(uri, localName, qName, attributes);
			if( qName.equals("feature") ) {
				id = attributes.getValue("id");
				version = attributes.getValue("version");
			} else if( /*qName.equals("includes") ||*/ qName.equals("plugin") ) {
				dep.add(new Dep(attributes.getValue("id"),attributes.getValue("version")));
			}
		}
	}

	static class Dep {
		private String id;
		private String version;

		public Dep(String id, String version) {
			this.id = id;
			this.version = version;
		}
	}

//	public static void main(String[] args) {
//		try {
//			FeatureToPom f2pom = new FeatureToPom(new File(OsgiToMaven.file,"features/org.eclipse.fx.target.feature_3.0.0.201612221915.jar"));
//			f2pom.publish();
//		} catch (Exception e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}
//	}
}
