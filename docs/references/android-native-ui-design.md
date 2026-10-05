<!-- Downloaded from Android Developers official UI documentation. -->
<!-- Downloaded: 2026-07-24. The HTML article bodies below were converted to plain text; navigation, scripts, and page chrome were excluded. -->
<!-- Authoritative online sources are retained before each snapshot. -->

# Android native UI design reference

# Official source: https://developer.android.com/design/ui/mobile

Android Developers

Design & Plan

UI Design

Mobile

Stay organized with collections

Save and categorize content based on your preferences.

Design for mobile

Create your app design using Android themes and components. Leverage Android’s unique design patterns and offerings to create a beautiful, usable, modern app.

Go to design foundations

Get started

Guides

Foundations

Fundamental concepts and principles of Android design.

See guidance

Guides

Styles

How to create beautiful visual design with color, type, motion, and theming for your app.

See guidance

Guides

Layout & content

How content should be structured within an app—from basics of adaptive layouts and grids to displaying graphics, and modern Android features, like edge-to-edge content.

See guidance

Guides

Behaviors & patterns

Interaction patterns that help your users understand, interact with, and control their experience in your app. Common behavior patterns include navigation, sharing, predictive back, and settings.

See guidance

Guides

Components

Leverage small, reusable, interactive, UI building blocks. Learn more about using Material Design components.

See guidance

Guides

Home screen

Extend your app to create unique experiences across the device by using system UI features like app widgets and notifications.

See guidance

Guides

Glossary

Understand common Android terminology.

See guidance

Guides

Accessibility

Benefit everyone by designing in accessibility support to help 15% of the world’s population communicate, learn, and work.

See guidance

Gallery

Tour the Android design gallery

Explore inspiring, optimized designs for all screen sizes and devices. Browse UI/UX templates for popular app categories, including media, creativity, games, and more.

View the gallery

Explore our kits

Explore our other Figma-based library kits, plugins, and the Material theme builder. Start building your Android app with modern themes, tools and user-generated dynamic color, or check out our 
Wear OS kits
 and 
TV kits
 to build for other devices.

Android UI kit

Get started designing for Android faster and easier with an introductory guide, styles, components, and system templates.

Go to Android UI kit

Android Design community

Explore the Android Design Figma community page with the latest templates, labs, and kits.

Go to Android Figma community

Theme builder

Use the web-based Material theme builder to design your next Android app.

Go to the theme builder

Use window size classes

Use compact, medium, and expanded window size classes to support different form
      factors for an optimal user experience.

Discover more about window size classes

Use a proven design system

Try Material Design 3

Material Design 3 is an open source, adaptable system of guidelines, components, and tools that support the best practices of user interface design.

Go to the Material Design website

Develop for mobile

Developer guides

Use our developer guides and reference to build your app design.

See the developer guides

Quality guides

Lay out your designs by following Android best practices.

See the quality guides

# Official source: https://developer.android.com/design/ui/mobile/guides/components/material-overview

Android Developers

Design & Plan

UI Design

Mobile

Guides

Material Components

Stay organized with collections

Save and categorize content based on your preferences.

A design system is a collection of reusable design decisions expressed as
guidance, components, and patterns. The system can be broken down into smallest
design primitives: things like color, type, or shape which build into larger
complex component pieces. For example, an icon and text label make up a button
component, while multiple buttons and a surface make up a card. Design systems
also come with a set of guidance composed of these existing design decisions
around the components and patterns.

Material Design is an open-source design system developed by Google to help you
build beautiful user-focused products. Material 3 is the latest iteration of
Material Design.

Material Design Components

Material Design provides an array of code-backed 
components
 
that are interactive building blocks for creating a user interface. These components
can be organized into five categories based on their purpose:
action, containment, navigation, selection, and text input.

Action components

Action components help people achieve an aim.

Material has multiple types of 
buttons
 to help define priority
of actions and interaction in different contexts. From 
FABs
 or

extended FABs
 for primary actions to supporting 
icon
buttons
 to selecting options with 
segmented
buttons
.

Figure 1:
 Action Components

Communication components

Communication components provide helpful information, by alerting users with

badges
, informing of status through 
progress
indicators
, and providing brief process messages with

snackbars
.

Figure 2:
 Communication

Containment components

Containment components hold information and actions – including other
components like buttons, menus, or chips. Most Material components use explicit
containment, grouping together related content and actions with visual objects:

cards
, 
dialogs
, 
bottom
sheets
, 
side sheets
,

carousels
, and 
tooltips
.

Lists
 can be provided with implicit containment or explicit by
showing visible 
dividers
. These components provide common
patterns for displaying groups of content.

Figure 3:
 Containment

Navigation components

Navigation components help people move through the UI. For mobile, the

navigation bar
 or 
navigation drawer
 
contain your primary navigation destinations. 
Tabs
,

the bottom app bar
, and 
the top app bar
 
provide different ways to navigate supporting information
and actions. Read more about how to work with
navigation within your 
layouts
.

Figure 4:
 Navigation

Selection components

Selection components let people specify choices. Whether building out a form
with 
checkboxes
 and 
radio buttons
, filtering
using 
chips
, or toggling settings with

switches
 and 
sliders
, selection components
allow users to control and input their decisions.

Figure 5:
 Selection

Text input components

Text input components let people enter and edit text. 
Text
fields
 allow users to enter text into a UI.

Figure 6:
 Text Input

Design systems for Compose

Read 
Design systems in Compose
 for details about how to use Compose to
more smoothly implement a design system and give your app a consistent look and
feel with theming, components, and other aspects of the design system.

# Official source: https://developer.android.com/design/ui/mobile/guides/foundations/accessibility

Android Developers

Design & Plan

UI Design

Mobile

Guides

Accessibility

Stay organized with collections

Save and categorize content based on your preferences.

According to a 
2011 report by the World Health Organization (WHO) and the
World Bank
, approximately 15% of the global population–that is,
about one in six people–experience a significant or temporary disability in
their lifetime. Accessibility in design, then, is 
fundamental
 to creating an
inclusive, usable, and high-quality app–it leads to the best results for users
and can prevent costly rework. Android ships with a variety of features to help
you build your app to support accessibility options by default.

Design for vision

Ensure your app's content is as legible as possible by checking color contrast
and text sizing, and that components are visually comprehensible and easy to
discern from each other.

Follow these guidelines to design for vision accessibility.

To allow users to adjust the font size, specify font size in 
scalable pixels
(sp)

Don't make the body size any smaller than 12 sp. This guideline aligns with the
Material typescale as a default.

Ensure the contrast between the background and text is at least 4.5:1. 
Learn
how to check color contrast
.

Use a 3:1 ratio between surfaces and non-text elements. For example, the ratio of a
background to an icon would be 3:1.

Use more than one visual affordance for actions like links.

Use Material's 
Accessible color system 
. This color system is
based on tonal palettes, and is central to making color schemes accessible by
default.

Figure 1: 
Example of text failing color contrast

Design for sound

TalkBack
 is a Google screen reader included on Android devices
that gives users eyes-free control. You can manually test this by 
exploring
your app with TalkBack
 or with the 
A11y scanner
.

Follow these guidelines to ensure your app is prepared for screen readers:

Describe UI elements
 in your code. Compose uses 
Semantics
properties
 to inform accessibility services about the information shown
in UI elements.

To satisfy Android framework requirements, provide additional textual
description of icons and images.

Set decorative item descriptions to null.

To allow skipping between blocks of actions and content, consider UI
granularity and group UI elements..

Check out Material's 
Design to Implementation Walk
, which
walks you through accessibility considerations and notation using Web Content
Accessibility Guidelines (WCAG).

Figure 2: 
UI elements labeled for accessibility: heading, hiding decorative image, and button label

Design for audio

Android provides features to enable users to interact with their devices through
a variety of voice commands and queries.

The 
Voice Access
 app for Android lets you control your device
with spoken commands. Use your voice to open apps, navigate, and edit text
hands-free.

Design for motor skill

Switch Access
 lets users interact with your Android device
using one or more devices, which can be helpful for users with limited dexterity
who have trouble interacting directly with a touch screen.

Manually test by 
exploring switch access
.

Don't rely on gestures to complete all actions; 
create accessibility
actions
 to support all user flows in your app.

Ensure all touch targets are at least 48 dp, even if this extends past the UI
element visual.

Consider 
haptic feedback
 to help inform the user with additional,
real-time sensory input.

Figure 3: 
The UI on the left lets the user delete only by swiping,
  while the UI on the right also provides an additional affordance in the form
  of a trash icon button.

