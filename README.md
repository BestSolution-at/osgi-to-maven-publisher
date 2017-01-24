# osgi-to-maven-publisher
Library to publish Eclipse p2 and OSGi r5 repositories to a maven repository

# Usage

This is a library so you can not use it directly to publish artifacts but
you have to create your own application to customize the publishing process.

An example useage is the publishing process used by e(fx)clipse to publish the 
runtime build artefacts.

```java
private static final String SNAPSHOT_URL = "https://oss.sonatype.org/content/repositories/snapshots/";
	private static final String RELEASE_URL = "https://oss.sonatype.org/service/local/staging/deploy/maven2/";
	private static final String REPOSITORY_ID = "sonatype";

	public static void main(String[] args) throws Throwable {
		Options options = new Options();
		options.addOption("snapshots", false, "Publish snapshots");
		options.addOption("mavenrepository",true, "Maven repository to publish the build to");
		options.addOption("mavenrepositoryid",true,"Id of the maven repository to publish to");
		options.addOption("p2repository", true, "P2 repository url");
		options.addOption("publishSubset", true, "Publishing subset allowed values ALL, PLATFORM, EFX");


		CommandLineParser parser = new DefaultParser();
		CommandLine cmd = parser.parse(options, args);

		String repository = cmd.hasOption("snapshots") ? "http://download.eclipse.org/efxclipse/runtime-nightly/site_assembly.zip" : null;
		if( cmd.hasOption("p2repository") ) {
			repository = cmd.getOptionValue("p2repository");
		}

		Path file = null;

		if( repository != null ) {
			URL url = new URL(repository);
			file = Files.createTempFile("p2-repository", ".zip");
			try ( InputStream in = url.openStream();
					OutputStream out = Files.newOutputStream(file) ) {
				byte[] buf = new byte[1024 * 500];
				int l;
				int total = 0;
				while( (l = in.read(buf)) != -1 ) {
					out.write(buf, 0, l);
					total += l;
					System.out.println("Loaded bytes " + new DecimalFormat("#,##0.00").format(total / 1024.0 / 1024.0) + " MB");
				}
			}
		} else {
			System.exit(1);
		}

		String mavenRepoUrl = cmd.hasOption("snapshots") ? SNAPSHOT_URL : RELEASE_URL;
		if( cmd.hasOption("mavenrepository") ) {
			mavenRepoUrl = cmd.getOptionValue("mavenrepository");
		}
		String mavenrepositoryId = REPOSITORY_ID;
		if( cmd.hasOption("mavenrepositoryid") ) {
			mavenrepositoryId = cmd.getOptionValue("mavenrepositoryid");
		}

		P2ToMavenSax p = new P2ToMavenSax( file.toAbsolutePath().toString(), mavenRepoUrl, mavenrepositoryId);
		p.setMavenReplacementLookup( b -> {
			if( b.getBundleId().equals("org.sat4j.core") ) {
				return Optional.of(new OsgiToMaven.MavenDep("org.ow2.sat4j","org.ow2.sat4j.core"));
			} else if( b.getBundleId().equals("org.sat4j.pb") ) {
				return Optional.of(new OsgiToMaven.MavenDep("org.ow2.sat4j","org.ow2.sat4j.pb"));
			}
			return Optional.empty();
		});
		p.setSourceEnforced( b -> ! b.getBundleId().equals("org.antlr.runtime"));
		p.setBundleFilter( b -> { return ! (b.getBundleId().startsWith("org.eclipse.emf.codegen")
				|| b.getBundleId().startsWith("org.eclipse.emf.mwe")
				|| b.getBundleId().startsWith("org.eclipse.fx.ui.workbench3")
				|| b.getBundleId().equals("org.eclipse.xtext.generator")
				|| b.getBundleId().equals("org.eclipse.xtext")); });
		p.setGroupIdResolver( b -> {
			if( b.getBundleId().startsWith("org.eclipse.fx") ) {
				return "at.bestsolution.efxclipse.rt";
			}
			return "at.bestsolution.eclipse";
		});

		if( cmd.hasOption("publishSubset") && "PLATFORM".equals(cmd.getOptionValue("publishSubset")) ) {
			p.setPublishFilter( b -> ! b.getBundleId().startsWith("org.eclipse.fx") );
		} else if( cmd.hasOption("publishSubset") && "EFX".equals(cmd.getOptionValue("publishSubset")) ) {
			p.setPublishFilter( b -> b.getBundleId().startsWith("org.eclipse.fx") );
		}

		p.publish();

		Files.delete(file);
	}
```
