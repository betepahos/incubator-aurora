'''Command-line tooling infrastructure for aurora client v2.

This provides a framework for a noun/verb command-line application. The application is structured
around a collection of basic objects (nouns) that can be manipulated by the command line, where
each type of object provides a collection of operations (verbs). Every command invocation
consists of the name of the noun, followed by one of the verbs for that noun, followed by other
arguments needed by the verb.

For example:
- To create a job, the noun is "job", the verb is "create":
  $ aurora job create us-west/www/prod/server server.aurora

- To find out the resource quota for a specific user, the noun is "user" and the verb is
  "get_quota":
  $ aurora user get_quota mchucarroll
'''

from __future__ import print_function

from abc import abstractmethod
import argparse
import sys


# Constants for standard return codes.
EXIT_OK = 0
EXIT_INVALID_CONFIGURATION = 3
EXIT_COMMAND_FAILURE = 4
EXIT_INVALID_COMMAND = 5
EXIT_INVALID_PARAMETER = 6
EXIT_NETWORK_ERROR = 7
EXIT_PERMISSION_VIOLATION = 8
EXIT_TIMEOUT = 9
EXIT_UNKNOWN_ERROR = 20


class Context(object):
  class Error(Exception): pass

  class ArgumentException(Error): pass

  class CommandError(Error):
    def __init__(self, code, msg):
      super(Context.CommandError, self).__init__(msg)
      self.msg = msg
      self.code = code

  def set_options(self, options):
    """Add the options object to a context.
    This is separated from the constructor to make patching tests easier.
    """
    self.options = options


class CommandOption(object):
  """A lightweight encapsulation of an argparse option specification, which can be used to
  define options that can be reused by multiple commands."""

  def __init__(self, *args, **kwargs):
    self.args = args
    self.kwargs = kwargs

  def add_to_parser(self, parser):
    parser.add_argument(*self.args, **self.kwargs)


class AuroraCommand(object):
  def setup_options_parser(self, argparser):
    """Set up command line options parsing for this command.
    This is a thin veneer over the standard python argparse system.
    :param argparser: the argument parser where this command can add its arguments.
    """
    pass

  def add_option(self, argparser, option):
    """Add a predefined argument encapsulated an a CommandOption to an argument parser."""
    if not isinstance(option, CommandOption):
      raise TypeError('Command option object must be an instance of CommandOption')
    option.add_to_parser(argparser)

  @property
  def help(self):
    """The help message for a command that will be used in the argparse help message"""

  @property
  def name(self):
    """The command name"""


class CommandLine(object):
  """The top-level object implementing a command-line application."""

  def __init__(self):
    self.nouns = {}
    self.parser = None

  def register_noun(self, noun):
    """Add a noun to the application"""
    if not isinstance(noun, Noun):
      raise TypeError('register_noun requires a Noun argument')
    self.nouns[noun.name] = noun

  def setup_options_parser(self):
    """ Build the options parsing for the application."""
    self.parser = argparse.ArgumentParser()
    subparser = self.parser.add_subparsers(dest='noun')
    for (name, noun) in self.nouns.items():
      noun_parser = subparser.add_parser(name, help=noun.help)
      noun.internal_setup_options_parser(noun_parser)

  def register_nouns(self):
    """This method should overridden by applications to register the collection of nouns
    that they can manipulate.
    """
    pass

  def execute(self, args):
    """Execute a command.
    :param args: the command-line arguments for the command. This only includes arguments
        that should be parsed by the application; it does not include sys.argv[0].
    """
    self.register_nouns()
    self.setup_options_parser()
    options = self.parser.parse_args(args)
    if options.noun not in self.nouns:
      raise ValueError('Unknown command: %s' % options.noun)
    noun = self.nouns[options.noun]
    context = noun.create_context()
    context.set_options(options)
    try:
      return noun.execute(context)
    except Context.CommandError as c:
      print('Error executing command: %s' % c.msg, file=sys.stderr)
      return c.code


class Noun(AuroraCommand):
  """A type of object manipulated by a command line application"""
  class InvalidVerbException(Exception): pass

  def __init__(self):
    super(Noun, self).__init__()
    self.verbs = {}

  def register_verb(self, verb):
    """Add an operation supported for this noun."""
    if not isinstance(verb, Verb):
      raise TypeError('register_verb requires a Verb argument')
    self.verbs[verb.name] = verb
    verb._register(self)

  def internal_setup_options_parser(self, argparser):
    """Internal driver for the options processing framework."""
    self.setup_options_parser(argparser)
    subparser = argparser.add_subparsers(dest='verb')
    for (name, verb) in self.verbs.items():
      vparser = subparser.add_parser(name, help=verb.help)
      verb.setup_options_parser(vparser)

  @classmethod
  def create_context(cls):
    """Commands access state through a context object. The noun specifies what kind
    of context should be created for this noun's required state.
    """
    pass

  @abstractmethod
  def setup_options_parser(self, argparser):
    pass

  def execute(self, context):
    if context.options.verb not in self.verbs:
      raise self.InvalidVerbException('Noun %s does not have a verb %s' %
          (self.name, context.options.verb))
    self.verbs[context.options.verb].execute(context)


class Verb(AuroraCommand):
  """An operation for a noun. Most application logic will live in verbs."""

  def _register(self, noun):
    """Create a link from a verb to its noun."""
    self.noun = noun

  @abstractmethod
  def setup_options_parser(self, argparser):
    pass

  def execute(self, context):
    pass


class AuroraCommandLine(CommandLine):
  """ An example implementation of a command line application using this framework.
  This should probably eventually get moved in to its own source file.
  """

  @classmethod
  def get_description(cls):
    return 'Aurora client command line'

  def register_nouns(self):
    from .jobs import Job
    self.register_noun(Job())


def main():
  cmd = AuroraCommandLine()
  cmd.execute(sys.argv[1:])


if __name__ == '__main__':
  main(sys.argv)
